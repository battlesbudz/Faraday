package com.battlesbudz.jarvis.v2.ai

/** Bounded text extraction for public user-supplied reference documents, never executable content. */
object ReferencePdfText {
    fun read(context: android.content.Context, bytes: ByteArray): String {
        require(bytes.size <= 2_000_000) { "Reference PDF exceeds byte limit" }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        return com.tom_roush.pdfbox.pdmodel.PDDocument.load(bytes).use { document ->
            require(!document.isEncrypted && document.numberOfPages <= 20) { "Reference PDF is encrypted or exceeds page limit" }
            com.tom_roush.pdfbox.text.PDFTextStripper().getText(document).take(50000)
        }
    }
}
