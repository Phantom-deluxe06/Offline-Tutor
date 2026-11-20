package com.offlinetutor.domain

/**
 * Pulls the first JSON object out of model output and repairs the common 1B-model
 * failures: prose around the JSON, trailing commas, smart quotes, and output truncated
 * by maxNewTokens (unclosed strings / brackets get closed).
 */
object JsonRepair {
    fun extractObject(raw: String): String? {
        val text = raw
            .replace('“', '"').replace('”', '"')
            .replace("```json", "").replace("```", "")
            // Seen on-device: `...?"{"A":"x"` (object re-opened mid-way) and `text."","answer"` (doubled quote).
            .replace(Regex("\"\\s*\\{\\s*\"(?=[A-Da-d]\"|answer\"|explanation\")"), "\",\"")
            .replace(Regex("\"\"(\\s*[,}])"), "\"$1")
        val start = text.indexOf('{')
        if (start < 0) return null

        val stack = ArrayDeque<Char>()
        var inString = false
        var escape = false
        var end = -1
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                    c == '\n' -> { /* raw newline inside a string; tolerated, fixed below */ }
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> stack.addLast('}')
                '[' -> stack.addLast(']')
                '}', ']' -> {
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isEmpty()) { end = i; break }
                }
            }
        }

        val body = if (end >= 0) {
            text.substring(start, end + 1)
        } else {
            // Truncated: close the open string, drop a dangling key/comma, then close brackets.
            val sb = StringBuilder(text.substring(start))
            if (inString) sb.append('"')
            var s = sb.toString().trimEnd()
            s = s.replace(Regex(",\\s*\"[^\"]*\"\\s*:?\\s*$"), "") // dangling `,"key":` or `,"partial`
            s = s.trimEnd().removeSuffix(",").removeSuffix(":")
            val closers = StringBuilder()
            while (stack.isNotEmpty()) closers.append(stack.removeLast())
            s + closers
        }
        return body
            .replace(Regex(",\\s*([}\\]])"), "$1")
            .replace("\n", " ")
    }
}
