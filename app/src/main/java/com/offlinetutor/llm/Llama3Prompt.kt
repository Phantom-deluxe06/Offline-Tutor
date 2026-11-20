package com.offlinetutor.llm

/**
 * Llama 3 chat template. The prompt text carries <|begin_of_text|> itself, so the
 * generation config uses numBos = 0 (the ExecuTorch LlamaDemo pattern).
 */
object Llama3Prompt {
    const val BOS = "<|begin_of_text|>"
    private const val EOT = "<|eot_id|>"

    enum class Role(val tag: String) { SYSTEM("system"), USER("user"), ASSISTANT("assistant") }

    data class Turn(val role: Role, val text: String)

    fun header(role: Role) = "<|start_header_id|>${role.tag}<|end_header_id|>\n\n"

    /** Builds the full prompt and leaves an open assistant header (plus optional prefill). */
    fun build(system: String, turns: List<Turn>, assistantPrefix: String = ""): String = buildString {
        append(BOS)
        append(header(Role.SYSTEM)).append(system.trim()).append(EOT)
        for (t in turns) append(header(t.role)).append(t.text.trim()).append(EOT)
        append(header(Role.ASSISTANT))
        append(assistantPrefix)
    }

    fun single(system: String, user: String, assistantPrefix: String = ""): String =
        build(system, listOf(Turn(Role.USER, user)), assistantPrefix)
}

/** Special tokens that may leak into the streamed text. */
object SpecialTokens {
    private val anySpecial = Regex("<\\|[a-z_0-9]+\\|>")
    private val endMarkers = listOf("<|eot_id|>", "<|end_of_text|>", "<|eom_id|>", "<|start_header_id|>")

    fun isEnd(piece: String) = endMarkers.any { piece.contains(it) }

    /** Text before the first end marker, with any other special tokens removed. */
    fun clean(piece: String): String {
        var cut = piece
        for (m in endMarkers) {
            val i = cut.indexOf(m)
            if (i >= 0) cut = cut.substring(0, i)
        }
        return cut.replace(anySpecial, "")
    }
}

/**
 * Rough token estimate (we have no tokenizer on the Kotlin side). Llama 3's tiktoken
 * vocabulary averages ~4 chars/token for English; Indic scripts are far heavier
 * (often ~1 token per character), which is why Tamil/Bengali prompts get less history.
 */
object TokenEstimator {
    fun estimate(text: String): Int {
        var ascii = 0
        var other = 0
        for (c in text) if (c.code < 128) ascii++ else other++
        return (ascii / 3.6).toInt() + (other * 0.9).toInt() + 1
    }
}
