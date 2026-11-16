package com.battlesbudz.jarvis.v2.ai

import java.io.Reader
import org.json.JSONArray
import org.json.JSONObject

/** Byte-level BPE reader for the tokenizer.json embedded in the LFM3 bundle. */
internal class OmniTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val merges: Map<String, Int>,
    private val special: List<Pair<String, Int>>,
    private val ignoreMerges: Set<String>,
    splitPattern: String,
) {
    private val byteEncoder = buildMap<Char, Int> {
        val bytes = ArrayList<Int>()
        for (b in 33..126) bytes += b
        for (b in 161..172) bytes += b
        for (b in 174..255) bytes += b
        val codePoints = bytes.toMutableList()
        var extra = 0
        for (b in 0..255) if (b !in bytes) { bytes += b; codePoints += 256 + extra++ }
        for (i in bytes.indices) put(codePoints[i].toChar(), bytes[i])
    }
    private val byteEncoderByByte = byteEncoder.entries.associate { it.value to it.key }
    private val byteDecoder = byteEncoder.entries.associate { it.key to it.value }
    private val splitRegex = Regex(splitPattern)

    fun encode(text: String): IntArray {
        val result = ArrayList<Int>()
        var offset = 0
        while (offset < text.length) {
            val token = special.firstOrNull { text.startsWith(it.first, offset) }
            if (token != null) {
                result += token.second
                offset += token.first.length
                continue
            }
            val nextSpecial = special.mapNotNull { text.indexOf(it.first, offset).takeIf { i -> i >= 0 } }.minOrNull() ?: text.length
            val ordinary = text.substring(offset, nextSpecial)
            var consumed = 0
            for (match in splitRegex.findAll(ordinary)) {
                check(match.range.first == consumed) { "Tokenizer split pattern skipped input at ${offset + consumed}" }
                consumed = match.range.last + 1
                val encodedBytes = match.value.toByteArray(Charsets.UTF_8).map { byteEncoderByByte.getValue(it.toInt() and 0xff) }
                val symbols = encodedBytes.map { it.toChar().toString() }.toMutableList()
                if (symbols.joinToString("") !in ignoreMerges) {
                    while (symbols.size > 1) {
                        var bestAt = -1
                        var bestRank = Int.MAX_VALUE
                        for (i in 0 until symbols.lastIndex) {
                            val rank = merges[symbols[i] + " " + symbols[i + 1]] ?: continue
                            if (rank < bestRank) { bestRank = rank; bestAt = i }
                        }
                        if (bestAt < 0) break
                        symbols[bestAt] = symbols[bestAt] + symbols[bestAt + 1]
                        symbols.removeAt(bestAt + 1)
                    }
                }
                for (symbol in symbols) result += (vocab[symbol] ?: error("Tokenizer has no id for BPE piece $symbol"))
            }
            check(consumed == ordinary.length) { "Tokenizer split pattern did not cover input at ${offset + consumed}" }
            offset = nextSpecial
        }
        return result.toIntArray()
    }

    fun decode(ids: IntArray): String {
        val byId = vocab.entries.associate { it.value to it.key }
        val specialById = special.associate { it.second to it.first }
        val bytes = ArrayList<Byte>()
        val out = StringBuilder()
        fun flush() {
            if (bytes.isNotEmpty()) { out.append(bytes.toByteArray().toString(Charsets.UTF_8)); bytes.clear() }
        }
        for (id in ids) {
            val marker = specialById[id]
            if (marker != null) { flush(); out.append(marker); continue }
            val piece = byId[id] ?: error("Tokenizer has no token id $id")
            for (char in piece) bytes += byteDecoder[char]?.toByte() ?: error("Invalid byte-BPE character U+${char.code.toString(16)}")
        }
        flush()
        return out.toString()
    }

    companion object {
        fun load(reader: Reader): OmniTokenizer {
            val root = JSONObject(reader.readText())
            val model = root.getJSONObject("model")
            val vocabJson = model.getJSONObject("vocab")
            val vocab = buildMap(vocabJson.length()) {
                val keys = vocabJson.keys()
                while (keys.hasNext()) { val key = keys.next(); put(key, vocabJson.getInt(key)) }
            }
            val mergeJson = model.optJSONArray("merges") ?: JSONArray()
            val merges = buildMap(mergeJson.length()) {
                for (i in 0 until mergeJson.length()) {
                    val merge = mergeJson.get(i)
                    val pair = if (merge is JSONArray) "${merge.getString(0)} ${merge.getString(1)}" else merge.toString()
                    put(pair, i)
                }
            }
            val specials = ArrayList<Pair<String, Int>>()
            val added = root.optJSONArray("added_tokens") ?: JSONArray()
            for (i in 0 until added.length()) {
                val item = added.getJSONObject(i)
                if (item.optBoolean("special", false)) specials += item.getString("content") to item.getInt("id")
            }
            // A few serializers omit added_tokens for special spellings; they remain in vocab.
            for ((text, id) in vocab) if (text.startsWith("<|") && text.endsWith("|>") && specials.none { it.first == text }) {
                specials += text to id
            }
            val ignoreValue = root.opt("ignore_merges").takeUnless { it == JSONObject.NULL }
                ?: model.opt("ignore_merges").takeUnless { it == JSONObject.NULL }
            val ignore = (ignoreValue as? JSONObject)?.let { obj ->
                buildSet { val keys = obj.keys(); while (keys.hasNext()) add(keys.next()) }
            } ?: (ignoreValue as? JSONArray)?.let { a -> buildSet { for (i in 0 until a.length()) add(a.getString(i)) } } ?: emptySet()
            val splitPattern = findSplitPattern(root.optJSONObject("pre_tokenizer"))
                ?: "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+\\s*|\\s+(?!\\S)|\\s+"
            return OmniTokenizer(vocab, merges, specials.sortedByDescending { it.first.length }, ignore, splitPattern)
        }

        private fun findSplitPattern(node: JSONObject?): String? {
            if (node == null) return null
            val pattern = node.optJSONObject("pattern")
            pattern?.optString("Regex")?.takeIf { it.isNotBlank() }?.let { return it }
            node.optString("pattern").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            val children = node.optJSONArray("pretokenizers")
            if (children != null) for (i in 0 until children.length()) {
                findSplitPattern(children.optJSONObject(i))?.let { return it }
            }
            return null
        }
    }
}
