package com.offlinetutor.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** 1b: one option of a diagnostic MCQ. Wrong options carry the misconception that leads to them. */
data class DiagOption(val text: String, val correct: Boolean, val code: Misconception? = null, val why: String? = null)

data class DiagMcq(
    val question: String,
    val concept: String,
    val difficulty: Int?,
    val options: List<DiagOption>,
    val explanation: String,
    /** Phase 2b: arithmetic expression for the answer, when the model gives one. */
    val expression: String? = null,
) {
    val answer: Int get() = options.indexOfFirst { it.correct }

    fun toMcq() = Mcq(question, options.map { it.text }, answer, explanation)

    fun shuffled(random: Random = Random.Default): DiagMcq = copy(options = options.shuffled(random))

    /** JSON array aligned with the options: null for the correct one, {code, why} for wrong ones. */
    fun metaJson(): String = JSONArray().apply {
        options.forEach { o ->
            put(if (o.correct || o.code == null) JSONObject.NULL else JSONObject().put("code", o.code.name).put("why", o.why.orEmpty()))
        }
    }.toString()
}

data class OptionTag(val code: Misconception, val why: String)

object OptionMeta {
    fun parse(json: String?): List<OptionTag?> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            (0 until a.length()).map { i ->
                val o = a.optJSONObject(i) ?: return@map null
                Misconception.of(o.optString("code"))?.let { OptionTag(it, o.optString("why")) }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun build(tags: List<OptionTag?>): String = JSONArray().apply {
        tags.forEach { t -> put(if (t == null) JSONObject.NULL else JSONObject().put("code", t.code.name).put("why", t.why)) }
    }.toString()
}

sealed interface DiagParse {
    data class Ok(val mcq: DiagMcq) : DiagParse
    /** Question, options and the key are fine, but some wrong options lack a valid tag → ask for tags only. */
    data class NeedsTags(val mcq: DiagMcq) : DiagParse
    data class Invalid(val reason: String) : DiagParse
}

/**
 * Parses the diagnostic schema. Accepts the spec's `"options":[{"t":..,"correct":true}, ...]` array and the
 * keyed form `"A":{...},"B":{...}` (on-device, the 1B model handled keyed objects but let arrays run away).
 * Valid = exactly 4 unique options, exactly one correct, every wrong option has a known code and a "why".
 */
object DiagnosticParser {
    private val optionPrefix = Regex("^\\s*(\\(?[A-Da-d1-4][).:\\-]\\s+)")

    fun parse(raw: String, fallbackConcept: String = ""): DiagParse {
        val json = JsonRepair.extractObject(raw) ?: return DiagParse.Invalid("no JSON object")
        val o = try { JSONObject(json) } catch (e: Exception) { return DiagParse.Invalid("bad JSON") }
        val question = (o.optString("q").ifBlank { o.optString("question") }).trim()
        val options = readOptions(o)
        val m = DiagMcq(
            question = question,
            concept = o.optString("concept").trim().ifBlank { fallbackConcept },
            difficulty = levelOf(o.optString("difficulty")),
            options = options,
            explanation = o.optString("explanation").trim(),
            expression = o.optString("expression").trim().ifBlank { null },
        )
        return validate(m)
    }

    fun validate(m: DiagMcq): DiagParse {
        if (m.options.size != 4) return DiagParse.Invalid("need 4 options, got ${m.options.size}")
        val base = McqParser.validate(Mcq(m.question, m.options.map { it.text }, m.answer.coerceAtLeast(0), m.explanation))
        if (m.options.count { it.correct } != 1) return DiagParse.Invalid("need exactly one correct option")
        when (base) {
            is McqParse.Invalid -> return DiagParse.Invalid(base.reason)
            is McqParse.Ok -> if (base.mcq.answer != m.answer) return DiagParse.Invalid("worked result contradicts the key")
        }
        val untagged = m.options.any { !it.correct && (it.code == null || it.why.isNullOrBlank()) }
        return if (untagged) DiagParse.NeedsTags(m) else DiagParse.Ok(m)
    }

    /** Applies the follow-up reply `{"B":{"misconception":"CODE","why":"..."}, ...}` (letters = wrong options in order). */
    fun applyTags(m: DiagMcq, raw: String): DiagParse {
        val json = JsonRepair.extractObject(raw) ?: return DiagParse.Invalid("tags: no JSON")
        val o = try { JSONObject(json) } catch (_: Exception) { return DiagParse.Invalid("tags: bad JSON") }
        val wrongIdx = m.options.indices.filter { !m.options[it].correct }
        val letters = listOf("B", "C", "D")
        val updated = m.options.toMutableList()
        wrongIdx.forEachIndexed { k, i ->
            val t = o.optJSONObject(letters[k]) ?: return@forEachIndexed
            val code = Misconception.of(t.optString("misconception").ifBlank { t.optString("code") })
            val why = t.optString("why").trim()
            if (code != null && why.isNotBlank()) updated[i] = updated[i].copy(code = code, why = why)
        }
        return validate(m.copy(options = updated))
    }

    private val blockHeader = Regex("^\\s*(?:\\(?([B-D])\\)|the letter(?: is)?\\s*:?\\s*([B-D])\\b)", RegexOption.IGNORE_CASE)
    private val codeName = Regex("(?i)\\b(SIGN[ _]ERROR|UNIT[ _]CONVERSION|FORMULA[ _]SWAP|OPERATION[ _]SWAP|CONCEPT[ _]CONFUSION|PARTIAL[ _]STEP|MISREAD[ _]QUESTION|OVERGENERALI[SZ]ATION)\\b")
    private val reasonLine = Regex("^\\s*(?:the\\s+)?(?:reason|why)\\s*(?:is(?: that)?|[:\\-])\\s*(.+)$", RegexOption.IGNORE_CASE)

    /** Description words → code, for when the model writes the description instead of the code name. */
    private val describedAs = listOf(
        Misconception.SIGN_ERROR to listOf("sign", "direction"),
        Misconception.UNIT_CONVERSION to listOf("unit"),
        Misconception.FORMULA_SWAP to listOf("formula"),
        Misconception.OPERATION_SWAP to listOf("operation", "added", "subtracted", "multiplied", "divided"),
        Misconception.PARTIAL_STEP to listOf("one step", "stopped", "partial"),
        Misconception.MISREAD_QUESTION to listOf("misread", "different question"),
        Misconception.OVERGENERALISATION to listOf("rule", "general"),
        Misconception.CONCEPT_CONFUSION to listOf("confus", "mixed up", "related idea"),
    )

    /**
     * Parses the tag reply into tags for B, C, D (null where missing). Handles the prompted block shape
     * ("B) …\nMistake type: CODE\nReason: …"), the shape the 1B model used on its own ("The letter: B / The
     * mistake type: CODE / The reason: …") and one-line "B) CODE - reason". A block naming 3+ different codes is
     * an echo of the instructions and is ignored.
     */
    private val pipeLine = Regex("^\\s*\\**\\s*([BCDbcd])\\s*[|)]\\s*([A-Za-z_ ]+?)\\s*\\|\\s*(.+?)\\s*$")

    /**
     * 2-fix-4: compact one-call format, one line per wrong option: `B|CODE|why (12 words max)`. Returns the tags
     * for B, C, D (null where a line is missing or its code is unknown). The reason is cut to 12 words.
     */
    fun parsePipeTags(raw: String): List<OptionTag?> {
        val found = HashMap<Char, OptionTag>()
        for (line in raw.lines()) {
            val m = pipeLine.find(line) ?: continue
            val letter = m.groupValues[1].uppercase()[0]
            if (letter in found) continue
            val code = Misconception.of(m.groupValues[2].trim().replace(' ', '_')) ?: continue
            val why = m.groupValues[3].trim().trim('"', '.', ' ').split(Regex("\\s+")).take(12).joinToString(" ")
            if (why.length >= 3 && Misconception.of(why) == null) found[letter] = OptionTag(code, why)
        }
        return listOf('B', 'C', 'D').map { found[it] }
    }

    fun parseTagLines(raw: String): List<OptionTag?> {
        val blocks = LinkedHashMap<Char, StringBuilder>()
        var current: Char? = null
        for (line in raw.lines()) {
            val h = blockHeader.find(line)
            if (h != null) {
                val letter = (h.groupValues[1].ifEmpty { h.groupValues[2] }).uppercase()[0]
                current = letter
                blocks.getOrPut(letter) { StringBuilder() }
            }
            current?.let { blocks.getValue(it).append(line).append('\n') }
        }
        return listOf('B', 'C', 'D').map { letter -> blocks[letter]?.toString()?.let { tagFromBlock(it) } }
    }

    private fun tagFromBlock(block: String): OptionTag? {
        val codes = codeName.findAll(block).map { Misconception.of(it.value) }.filterNotNull().toList()
        if (codes.toSet().size >= 3) return null // echoed list of mistake types, not an answer
        val typeLine = block.lines().firstOrNull { it.contains("mistake type", true) || it.contains("type:", true) }.orEmpty()
        val code = codes.firstOrNull()
            ?: describedAs.firstOrNull { (_, words) -> words.any { typeLine.contains(it, true) } }?.first
            ?: return null
        val why = block.lines().firstNotNullOfOrNull { reasonLine.find(it)?.groupValues?.get(1) }
            // One-line shape: "B) FORMULA_SWAP - reason" or "C) operation swap: reason".
            ?: block.lines().firstNotNullOfOrNull { l ->
                codeName.find(l)?.let { m -> l.substring(m.range.last + 1).trimStart(' ', '-', '–', '—', ':', ',', '.').trim().ifEmpty { null } }
            }
            ?: return null
        val clean = why.trim().substringBefore("\n").let { s -> s.split(Regex("(?<=[.!?])\\s")).first() }.trim().trimEnd('.').take(140)
        return if (clean.length >= 3) OptionTag(code, clean) else null
    }

    private val numericOption = Regex("^\\s*(?:rs\\.?|₹)?\\s*-?\\d", RegexOption.IGNORE_CASE)
    private val arithmeticCodes = setOf(Misconception.SIGN_ERROR, Misconception.UNIT_CONVERSION, Misconception.FORMULA_SWAP, Misconception.OPERATION_SWAP)

    /**
     * Builds a diagnostic MCQ from a validated plain MCQ plus tags for its wrong options (in option order).
     * Measured on-device: the 1B model put arithmetic codes on word answers ("Respiration" → SIGN_ERROR), so on a
     * non-numeric option an arithmetic code is mapped to CONCEPT_CONFUSION (its reason text is kept).
     */
    fun fromMcq(m: Mcq, concept: String, tags: List<OptionTag?>): DiagParse {
        val wrong = m.options.indices.filter { it != m.answer }
        val opts = m.options.mapIndexed { i, t ->
            val k = wrong.indexOf(i)
            val tag = if (k >= 0) tags.getOrNull(k) else null
            val code = tag?.code?.let { c -> if (c in arithmeticCodes && !numericOption.containsMatchIn(t)) Misconception.CONCEPT_CONFUSION else c }
            DiagOption(t, i == m.answer, code, tag?.why)
        }
        return validate(DiagMcq(m.question, concept, null, opts, m.explanation, m.expression))
    }

    private fun levelOf(s: String): Int? = when (s.trim().lowercase()) {
        "easy" -> AdaptiveDifficulty.EASY
        "medium" -> AdaptiveDifficulty.MEDIUM
        "hard" -> AdaptiveDifficulty.HARD
        else -> null
    }

    private fun readOptions(o: JSONObject): List<DiagOption> {
        val arr = o.optJSONArray("options")
        val raw: List<Any?> = if (arr != null) (0 until arr.length()).map { arr.opt(it) }
        else listOf("A", "B", "C", "D").mapNotNull { k -> o.opt(k) ?: o.opt(k.lowercase()) }
        val answerLetter = o.optString("answer").trim().uppercase().takeIf { it.length == 1 }
        return raw.mapIndexed { i, v ->
            when (v) {
                is JSONObject -> DiagOption(
                    text = v.optString("t").ifBlank { v.optString("text") }.replace(optionPrefix, "").trim(),
                    correct = v.optBoolean("correct", false) || v.optString("correct").equals("true", true),
                    code = Misconception.of(v.optString("misconception").ifBlank { v.optString("code") }),
                    why = v.optString("why").trim().ifBlank { null },
                )
                // Plain string options (model fell back to the old format): correctness from "answer".
                is String -> DiagOption(v.replace(optionPrefix, "").trim(), answerLetter == ('A' + i).toString())
                else -> DiagOption("", false)
            }
        }
    }
}
