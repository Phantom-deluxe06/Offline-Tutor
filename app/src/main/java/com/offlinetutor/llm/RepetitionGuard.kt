package com.offlinetutor.llm

/**
 * App-level repetition guard. The ExecuTorch LLM API has no repetition penalty, so a
 * 1B model can fall into loops. We watch the stream and stop generation when we see:
 *  - the same non-trivial line 3 times,
 *  - a word n-gram (n = 6) occurring 3 times,
 *  - the tail of the text repeating itself back-to-back 3 times (period 2..20 words),
 *  - one character repeated 40+ times,
 *  - nothing but whitespace after [blankTokenLimit] tokens.
 */
class RepetitionGuard(
    private val ngram: Int = 6,
    private val maxNgramHits: Int = 3,
    private val maxLineRepeats: Int = 3,
    private val blankTokenLimit: Int = 48,
) {
    var reason: String? = null
        private set

    private val text = StringBuilder()
    private val lineCounts = HashMap<String, Int>()
    private val ngramCounts = HashMap<String, Int>()
    private val words = ArrayList<String>()
    private var pendingWord = StringBuilder()
    private var pendingLine = StringBuilder()
    private var tokens = 0
    private var sameCharRun = 0
    private var lastChar: Char? = null

    /** Feed one streamed piece; returns true when generation should be stopped. */
    fun feed(piece: String): Boolean {
        if (reason != null) return true
        tokens++
        text.append(piece)
        for (c in piece) {
            onChar(c)
            if (reason != null) return true
        }
        if (tokens >= blankTokenLimit && text.isBlank()) reason = "blank output"
        return reason != null
    }

    private fun onChar(c: Char) {
        if (c == lastChar && !c.isWhitespace()) {
            sameCharRun++
            if (sameCharRun >= 40) reason = "char run '$c'"
        } else {
            sameCharRun = 1
        }
        lastChar = c

        if (c == '\n') {
            endWord()
            endLine()
        } else {
            pendingLine.append(c)
            // Any non-alphanumeric char ends a word, so loops without spaces
            // (e.g. JSON arrays like "60","90","30",...) are still caught.
            if (c.isLetterOrDigit()) pendingWord.append(c) else endWord()
        }
    }

    private fun endLine() {
        val line = normalize(pendingLine.toString())
        pendingLine = StringBuilder()
        if (line.length < 8) return
        val n = (lineCounts[line] ?: 0) + 1
        lineCounts[line] = n
        if (n >= maxLineRepeats) reason = "repeated line"
    }

    private fun endWord() {
        if (pendingWord.isEmpty()) return
        val w = pendingWord.toString().lowercase().trim { !it.isLetterOrDigit() }
        pendingWord = StringBuilder()
        if (w.isEmpty()) return
        words.add(w)
        if (words.size >= ngram) {
            val key = words.subList(words.size - ngram, words.size).joinToString(" ")
            val n = (ngramCounts[key] ?: 0) + 1
            ngramCounts[key] = n
            if (n >= maxNgramHits) {
                reason = "repeated $ngram-gram"
                return
            }
        }
        if (tailLoops()) reason = "tail loop"
    }

    /** Detects the last p words repeating 3 times in a row. */
    private fun tailLoops(): Boolean {
        val size = words.size
        for (p in 2..20) {
            if (size < p * 3) break
            var loop = true
            for (i in 0 until p) {
                val a = words[size - 1 - i]
                if (a != words[size - 1 - i - p] || a != words[size - 1 - i - 2 * p]) {
                    loop = false; break
                }
            }
            if (loop) return true
        }
        return false
    }

    private fun normalize(s: String) =
        s.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }.trim().replace(Regex("\\s+"), " ")
}

/**
 * Detects when the first top-level JSON object is closed so we can stop early
 * instead of letting the model ramble until maxNewTokens.
 */
class JsonCompletionDetector {
    private var depth = 0
    private var started = false
    private var inString = false
    private var escape = false
    var complete = false
        private set

    fun feed(piece: String): Boolean {
        if (complete) return true
        for (c in piece) {
            if (!started) {
                if (c == '{') { started = true; depth = 1 }
                continue
            }
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) { complete = true; return true }
                }
            }
        }
        return false
    }
}
