package com.offlinetutor.llm

import org.json.JSONObject

/**
 * Parsed from LlmCallback.onStats(). Field names are not guaranteed across ExecuTorch
 * versions, so every value is optional and several candidate keys are tried.
 * The C++ runner reports timestamps in units of SCALING_FACTOR_UNITS_PER_SECOND (1000 = ms).
 */
data class GenStats(
    val promptTokens: Int?,
    val generatedTokens: Int?,
    val ttftMs: Long?,
    val prefillTokPerSec: Double?,
    val decodeTokPerSec: Double?,
    val inferenceMs: Long?,
    val modelLoadMs: Long?,
)

object StatsParser {
    fun parse(json: String?): GenStats? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val scale = num(o, "SCALING_FACTOR_UNITS_PER_SECOND", "scaling_factor_units_per_second") ?: 1000.0
            fun ms(v: Double?) = v?.let { it * 1000.0 / scale }

            val promptTokens = num(o, "prompt_tokens", "num_prompt_tokens", "promptTokens")?.toInt()
            val genTokens = num(o, "generated_tokens", "num_generated_tokens", "generatedTokens")?.toInt()
            val infStart = ms(num(o, "inference_start_ms", "inferenceStartMs"))
            val infEnd = ms(num(o, "inference_end_ms", "inferenceEndMs"))
            val promptEnd = ms(num(o, "prompt_eval_end_ms", "promptEvalEndMs"))
            val firstTok = ms(num(o, "first_token_ms", "firstTokenMs"))
            val loadStart = ms(num(o, "model_load_start_ms", "modelLoadStartMs"))
            val loadEnd = ms(num(o, "model_load_end_ms", "modelLoadEndMs"))

            val ttft = if (firstTok != null && infStart != null) (firstTok - infStart).toLong() else null
            val prefillTps = if (promptTokens != null && promptEnd != null && infStart != null && promptEnd > infStart)
                promptTokens * 1000.0 / (promptEnd - infStart) else null
            val decodeStart = promptEnd ?: firstTok
            val decodeTps = if (genTokens != null && infEnd != null && decodeStart != null && infEnd > decodeStart)
                genTokens * 1000.0 / (infEnd - decodeStart)
            else num(o, "tokens_per_second", "tps", "decode_tps")
            GenStats(
                promptTokens = promptTokens,
                generatedTokens = genTokens,
                ttftMs = ttft,
                prefillTokPerSec = prefillTps,
                decodeTokPerSec = decodeTps,
                inferenceMs = if (infStart != null && infEnd != null) (infEnd - infStart).toLong() else null,
                modelLoadMs = if (loadStart != null && loadEnd != null && loadEnd > loadStart) (loadEnd - loadStart).toLong() else null,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun num(o: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            if (!o.has(k) || o.isNull(k)) continue
            val d = when (val v = o.opt(k)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (d != null && !d.isNaN()) return d
        }
        return null
    }
}
