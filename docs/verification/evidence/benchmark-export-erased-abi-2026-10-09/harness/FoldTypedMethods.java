import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.Opcodes;
import org.jetbrains.org.objectweb.asm.tree.ClassNode;
import org.jetbrains.org.objectweb.asm.tree.MethodNode;

/** Host linkage reproduction only, not an R8 implementation or Android verifier.
 * Copies real AndroidX method bodies into their erased bridges, removing exactly
 * the typed synchronous/parse descriptors absent from the Build 1247 app DEX.
 * No Android, contract, or result behavior is replaced with a hand-written stub.
 */
public final class FoldTypedMethods {
    public static void main(String[] args) throws Exception {
        String owner = "androidx/activity/result/contract/ActivityResultContracts$CreateDocument";
        ClassNode node = new ClassNode();
        try (JarFile jar = new JarFile(args[0])) {
            new ClassReader(jar.getInputStream(jar.getJarEntry(owner + ".class")).readAllBytes()).accept(node, 0);
        }
        String result = "Landroidx/activity/result/contract/ActivityResultContract$SynchronousResult;";
        for (String[] pair : List.of(
                new String[]{"getSynchronousResult", "(Landroid/content/Context;Ljava/lang/String;)" + result,
                        "(Landroid/content/Context;Ljava/lang/Object;)" + result},
                new String[]{"parseResult", "(ILandroid/content/Intent;)Landroid/net/Uri;",
                        "(ILandroid/content/Intent;)Ljava/lang/Object;"})) {
            MethodNode typed = node.methods.stream().filter(m -> m.name.equals(pair[0]) && m.desc.equals(pair[1])).findFirst().orElseThrow();
            MethodNode bridge = node.methods.stream().filter(m -> m.name.equals(pair[0]) && m.desc.equals(pair[2])).findFirst().orElseThrow();
            node.methods.remove(bridge);
            typed.desc = bridge.desc;
            typed.signature = null;
            typed.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC;
            // Null checks accept Object; the String input is otherwise unused.
            // Uri and Object share ARETURN; parse's arguments and frames are unchanged.
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1], owner + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
