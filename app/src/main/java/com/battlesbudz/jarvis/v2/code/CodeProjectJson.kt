package com.battlesbudz.jarvis.v2.code

/**
 * The `project.json` codec (code-and-export scope section 3.1): the flat
 * machine state of one project — id, name, created/updated stamps, round
 * counter, export package name — written and read by [CodeProjectStore].
 *
 * The format is rendered by hand and parsed by a small strict reader for
 * exactly this shape, so the code package needs no JSON dependency. Names
 * are escaped on write (quotes, backslash, control chars) and unescaped
 * on read, so a project name survives the round trip byte-for-byte.
 * Anything malformed parses to null; the store treats an unreadable state
 * file as no project rather than guessing.
 */
internal object CodeProjectJson {

    fun render(project: CodeProjectStore.CodeProject): String = buildString {
        append("{\n")
        append("  \"id\": ").append(quote(project.id)).append(",\n")
        append("  \"name\": ").append(quote(project.name)).append(",\n")
        append("  \"createdAtMillis\": ").append(project.createdAtMillis).append(",\n")
        append("  \"updatedAtMillis\": ").append(project.updatedAtMillis).append(",\n")
        append("  \"rounds\": ").append(project.rounds).append(",\n")
        append("  \"exportPackageName\": ").append(quote(project.exportPackageName)).append('\n')
        append("}\n")
    }

    fun parse(text: String): CodeProjectStore.CodeProject? {
        val fields = try {
            Parser(text).parseObject()
        } catch (e: Exception) {
            return null
        }
        val id = fields["id"] as? String ?: return null
        val name = fields["name"] as? String ?: return null
        val created = fields["createdAtMillis"] as? Long ?: return null
        val updated = fields["updatedAtMillis"] as? Long ?: return null
        val rounds = (fields["rounds"] as? Long)?.toInt() ?: return null
        val pkg = fields["exportPackageName"] as? String ?: return null
        return CodeProjectStore.CodeProject(id, name, created, updated, rounds, pkg)
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** A strict reader for one flat object of string and integer values. */
    private class Parser(private val text: String) {
        private var pos = 0

        fun parseObject(): Map<String, Any> {
            val fields = LinkedHashMap<String, Any>()
            skipWhitespace()
            expect('{')
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return fields
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = if (peek() == '"') parseString() else parseNumber()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return fields
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                if (pos >= text.length) throw IllegalArgumentException("unterminated string")
                when (val c = text[pos++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (pos >= text.length) throw IllegalArgumentException("dangling escape")
                        when (val e = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                if (pos + 4 > text.length) {
                                    throw IllegalArgumentException("short unicode escape")
                                }
                                out.append(text.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape: $e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun parseNumber(): Long {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            if (pos == start || (pos == start + 1 && text[start] == '-')) {
                throw IllegalArgumentException("bad number at $start")
            }
            return text.substring(start, pos).toLong()
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= text.length) throw IllegalArgumentException("unexpected end of input")
            return text[pos]
        }

        private fun next(): Char {
            val c = peek()
            pos++
            return c
        }

        private fun expect(c: Char) {
            if (next() != c) throw IllegalArgumentException("expected '$c' at ${pos - 1}")
        }
    }
}
