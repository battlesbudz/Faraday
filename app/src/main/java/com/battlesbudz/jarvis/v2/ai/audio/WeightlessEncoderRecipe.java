package com.battlesbudz.jarvis.v2.ai.audio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;

/**
 * Streaming, fail-closed reassembler for the one audited Gemma4 E2B recipe.
 * Uses standard Java APIs available on Android API 26+. No FlatBuffer library,
 * native inference, Python, network, credentials, or model payload in assets.
 * Call from a background worker using app-private, trusted local file paths.
 * Structural assets and the following SHA pins must ship together in reviewed code.
 * This is a host-tested integration primitive, not a tested Android app release.
 */
public final class WeightlessEncoderRecipe {
    public static final int RECIPE_BYTES = 86_196;
    public static final int LITERALS_BYTES = 134_002;
    public static final int LITERALS_DECODED_BYTES = 1_068_632;
    public static final String LITERALS_DECODED_SHA256 = "e87747bc9d1289ff15fdf0f49a4834ecc849823fe11bb4671fb11921fa0b9cb0";
    public static final long SOURCE_BYTES = 2588147712L;
    public static final long OUTPUT_BYTES = 103668112L;
    public static final String SOURCE_SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c";
    public static final String OUTPUT_SHA256 = "d5c50b140ace235717e6713d287e73ccfa4f32d0090e1cceb9d00714da850a1b";
    public static final String RECIPE_SHA256 = "f41105fecf5a86c0ab2486182d4256b98bf33c3a71f58c2b4f8e9a37b5cd86cc";
    public static final String LITERALS_SHA256 = "c3093aa1f9bd5ff9cfe3c0a61df722d0bce806a5a4f2f1b747b0ffd91d15d2f1";
    private static final int CHUNK_BYTES = 65536;
    private static final byte[] MAGIC = new byte[] {'G','4','E','2','B','W','R','1'};
    private static final long[][] SECTIONS = {
        {1393082368L, 94052280L}, {1487142912L, 9441244L}, {1496596480L, 6772L}
    };
    private WeightlessEncoderRecipe() {}

    private static final class Span {
        final long destination, source, length;
        final byte[] sha;
        Span(long destination, long source, long length, byte[] sha) {
            this.destination=destination; this.source=source; this.length=length; this.sha=sha;
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new AssertionError(ex); }
    }
    private static void require(boolean condition, String reason) throws IOException {
        if (!condition) throw new IOException(reason);
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean()))
            throw new InterruptedIOException("Reassembly cancelled");
    }
    private static String hex(byte[] bytes) {
        char[] alphabet="0123456789abcdef".toCharArray(); char[] result=new char[bytes.length*2];
        for(int i=0;i<bytes.length;i++) {result[2*i]=alphabet[(bytes[i]&255)>>>4];result[2*i+1]=alphabet[bytes[i]&15];}
        return new String(result);
    }
    private static byte[] readBytes(DataInputStream in, int count) throws IOException {
        byte[] bytes=new byte[count]; in.readFully(bytes); return bytes;
    }
    private static void verifyFile(File file, String expected, BooleanSupplier cancelled) throws IOException {
        MessageDigest h=digest(); byte[] chunk=new byte[CHUNK_BYTES];
        try(InputStream in=new BufferedInputStream(new FileInputStream(file),CHUNK_BYTES)) {
            for(int n;(n=in.read(chunk))!=-1;) {checkCancelled(cancelled);h.update(chunk,0,n);}
        }
        require(hex(h.digest()).equals(expected), "Asset SHA-256 mismatch: "+file.getName());
    }
    private static Span[] readRecipe(File recipe) throws IOException {
        try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(recipe)))) {
            require(Arrays.equals(readBytes(in,8),MAGIC),"Bad recipe magic");
            require(in.readLong()==SOURCE_BYTES && in.readLong()==OUTPUT_BYTES,"Wrong recipe lengths");
            require(hex(readBytes(in,32)).equals(SOURCE_SHA256),"Wrong source pin");
            require(hex(readBytes(in,32)).equals(OUTPUT_SHA256),"Wrong output pin");
            require(hex(readBytes(in,32)).equals(LITERALS_SHA256),"Wrong literal pin");
            int count=in.readInt();require(count>0 && count<=4096,"Invalid span count");
            Span[] spans=new Span[count];long previousEnd=0;
            for(int i=0;i<count;i++) {
                long dst=in.readLong(),src=in.readLong(),length=in.readLong();
                require(length>0 && dst>=previousEnd && dst<=OUTPUT_BYTES && length<=OUTPUT_BYTES-dst,
                        "Invalid output span");
                require(src>=0 && src<=SOURCE_BYTES && length<=SOURCE_BYTES-src,"Invalid source span");
                boolean inSection=false;
                for(long[] section:SECTIONS)
                    if(src>=section[0] && src<=section[0]+section[1] && length<=section[0]+section[1]-src)
                        inSection=true;
                require(inSection,"Source span outside approved model sections");
                spans[i]=new Span(dst,src,length,readBytes(in,32));previousEnd=dst+length;
            }
            require(in.read()==-1,"Trailing recipe bytes");return spans;
        }
    }
    private static void literals(InputStream in, OutputStream out, long size, byte[] chunk,
                                 MessageDigest complete, BooleanSupplier cancelled) throws IOException {
        while(size>0) {
            checkCancelled(cancelled);int n=in.read(chunk,0,(int)Math.min(size,chunk.length));
            if(n<0)throw new EOFException("Truncated structural literals");
            if(n==0)continue;
            out.write(chunk,0,n);complete.update(chunk,0,n);size-=n;
        }
    }
    /**
     * Reconstruct to an absent destination, verifying full source, recipe, literal,
     * per-span, and full output hashes. A private temporary file is published only
     * after validation by a same-directory atomic move. All failures clean it up.
     * The caller owns cleanup of a successfully installed output when no longer needed.
     */
    public static void reconstruct(File bundle, File recipe, File literalAsset, File destination,
                                   BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        File output=destination.getCanonicalFile();File parent=output.getParentFile();
        require(parent!=null && parent.isDirectory(),"Destination parent must already exist");
        require(!output.exists(),"Destination already exists");
        require(!output.equals(bundle.getCanonicalFile()),"Source and destination are the same");
        verifyFile(recipe,RECIPE_SHA256,cancelled);verifyFile(literalAsset,LITERALS_SHA256,cancelled);
        Span[] spans=readRecipe(recipe);byte[] chunk=new byte[CHUNK_BYTES];
        File temporary=null;boolean installed=false;
        try(RandomAccessFile source=new RandomAccessFile(bundle,"r")) {
            require(source.length()==SOURCE_BYTES,"Source byte length mismatch");
            MessageDigest sourceHash=digest();
            for(int n;(n=source.read(chunk))!=-1;) {checkCancelled(cancelled);sourceHash.update(chunk,0,n);}
            require(hex(sourceHash.digest()).equals(SOURCE_SHA256),"Source SHA-256 mismatch");
            checkCancelled(cancelled);
            temporary=File.createTempFile(".gemma-encoder-", ".tmp",parent);
            MessageDigest complete=digest();long cursor=0;
            try(InputStream literals=new GZIPInputStream(new BufferedInputStream(new FileInputStream(literalAsset)),CHUNK_BYTES);
                FileOutputStream fileOut=new FileOutputStream(temporary);
                OutputStream out=new BufferedOutputStream(fileOut,CHUNK_BYTES)) {
                for(Span span:spans) {
                    literals(literals,out,span.destination-cursor,chunk,complete,cancelled);
                    source.seek(span.source);MessageDigest copied=digest();long left=span.length;
                    while(left>0) {
                        checkCancelled(cancelled);int n=source.read(chunk,0,(int)Math.min(left,chunk.length));
                        if(n<0)throw new EOFException("Source truncated during reconstruction");
                        out.write(chunk,0,n);copied.update(chunk,0,n);complete.update(chunk,0,n);left-=n;
                    }
                    require(Arrays.equals(copied.digest(),span.sha),"Source payload mismatch");
                    cursor=span.destination+span.length;
                }
                literals(literals,out,OUTPUT_BYTES-cursor,chunk,complete,cancelled);
                require(literals.read()==-1,"Excess structural literals");
                require(hex(complete.digest()).equals(OUTPUT_SHA256),"Final output SHA-256 mismatch");
                out.flush();fileOut.getFD().sync();
            }
            require(temporary.length()==OUTPUT_BYTES,"Final output length mismatch");
            checkCancelled(cancelled);require(!output.exists(),"Destination appeared during reconstruction");
            Files.move(temporary.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE);
            installed=true;
        } finally {
            if(!installed && temporary!=null && temporary.exists() && !temporary.delete())
                throw new IOException("Failed to remove incomplete temporary file: "+temporary.getName());
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=4 && args.length!=5)
            throw new IllegalArgumentException("bundle.litertlm source-copy-recipe.bin structural-literals.bin.gzip output.tflite [cancel]");
        long started=System.nanoTime();final boolean cancel=args.length==5 && args[4].equals("cancel");
        reconstruct(new File(args[0]),new File(args[1]),new File(args[2]),new File(args[3]),()->cancel);
        System.out.println("Reconstructed "+OUTPUT_BYTES+" bytes; SHA-256 "+OUTPUT_SHA256+
                           "; elapsed_seconds="+((System.nanoTime()-started)/1_000_000_000.0));
    }
}
