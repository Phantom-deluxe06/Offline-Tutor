package com.offlinetutor.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class Mcq(
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
    /** Phase 2b: arithmetic for the answer when the model gives one (verified with exp4j). */
    val expression: String? = null,
) {
    /** 1B models put the right answer first far too often; shuffle and remap the key. */
    fun shuffled(random: Random = Random.Default): Mcq {
        val order = options.indices.shuffled(random)
        return copy(options = order.map { options[it] }, answer = order.indexOf(answer))
    }
}

sealed interface McqParse {
    data class Ok(val mcq: Mcq) : McqParse
    data class Invalid(val reason: String) : McqParse
}

/**
 * Parses + validates model MCQ JSON. Valid means: non-empty question, exactly 4
 * unique non-empty options, answer index in range, non-empty explanation.
 */
object McqParser {
    private val optionPrefix = Regex("^\\s*(\\(?[A-Da-d1-4][).:\\-]\\s+|option\\s+[A-Da-d1-4][).:\\-]?\\s*)")

    fun parse(raw: String): McqParse {
        val json = JsonRepair.extractObject(raw) ?: return McqParse.Invalid("no JSON object")
        val o = try { JSONObject(json) } catch (e: Exception) { return McqParse.Invalid("bad JSON: ${e.message}") }

        val question = firstString(o, "question", "q", "stem").trim()
        val options = readOptions(o)
        val explanation = firstString(o, "explanation", "reason", "rationale").trim()
        val answer = readAnswer(o, options)

        return validate(Mcq(question, options, answer, explanation))
    }

    /**
     * Question + 4 unique options but no usable answer/explanation (the 1B model often closes the
     * object right after option D). Returned so the caller can ask for the answer in a short follow-up.
     */
    fun parseStem(raw: String): Mcq? {
        val json = JsonRepair.extractObject(raw) ?: return null
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val m = Mcq(firstString(o, "question", "q", "stem").trim(), readOptions(o), -1, "")
        if (m.question.length < 8 || m.options.size != 4 || m.options.any { it.isBlank() }) return null
        if (m.options.map { QuestionDeduper.normalize(it) }.toSet().size != 4) return null
        return m
    }

    /** Parses the follow-up reply `{"answer":"B","explanation":"..."}` into the stem. */
    fun completeWith(stem: Mcq, raw: String): McqParse {
        val json = JsonRepair.extractObject(raw) ?: return McqParse.Invalid("follow-up: no JSON")
        val o = try { JSONObject(json) } catch (e: Exception) { return McqParse.Invalid("follow-up: bad JSON") }
        return validate(stem.copy(answer = readAnswer(o, stem.options), explanation = firstString(o, "explanation", "reason").trim()))
    }

    fun validate(m: Mcq): McqParse {
        if (m.question.length < 8) return McqParse.Invalid("question missing")
        if (m.options.size != 4) return McqParse.Invalid("need 4 options, got ${m.options.size}")
        if (m.options.any { it.isBlank() }) return McqParse.Invalid("blank option")
        val norm = m.options.map { QuestionDeduper.normalize(it) }
        if (norm.toSet().size != 4) return McqParse.Invalid("duplicate options")
        if (m.answer !in 0..3) return McqParse.Invalid("answer index out of range")
        if (m.explanation.isBlank()) return McqParse.Invalid("explanation missing")
        // Seen on-device: the 1B model copies the prompt's placeholders ("one or two sentences", "...").
        val optionsAreKeys = m.options.map { it.trim().uppercase() } == listOf("A", "B", "C", "D")
        if (optionsAreKeys || isPlaceholder(m.explanation) || isPlaceholder(m.question) || m.options.any { isPlaceholder(it) }) {
            return McqParse.Invalid("placeholder text copied from the prompt")
        }
        return checkWorkedResult(m)
    }

    private val resultRe = Regex("=\\s*(-?\\d+(?:\\.\\d+)?)")
    private val leadingNumber = Regex("^\\s*(?:rs\\.?|₹)?\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)

    /**
     * Seen on-device: explanation "12 loaves * 7 days = 84 loaves" with the key on "12" and no "84" option.
     * When the explanation states a numeric result and the options are numbers, the key must point at that
     * number: fix the key if another option has it, reject the question if none does.
     */
    private fun checkWorkedResult(m: Mcq): McqParse {
        val result = resultRe.findAll(m.explanation).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull() ?: return McqParse.Ok(m)
        val values = m.options.map { o -> leadingNumber.find(o)?.groupValues?.get(1)?.toDoubleOrNull() }
        if (values.any { it == null }) return McqParse.Ok(m) // not a numeric question; nothing to check
        val idx = values.indexOfFirst { kotlin.math.abs(it!! - result) < 1e-6 }
        return when {
            idx == -1 -> McqParse.Invalid("worked result ${result} is not among the options")
            idx != m.answer -> McqParse.Ok(m.copy(answer = idx))
            else -> McqParse.Ok(m)
        }
    }

    private val placeholders = setOf("one or two sentences", "one or two sentence")

    private fun isPlaceholder(s: String): Boolean =
        QuestionDeduper.normalize(s) in placeholders || s.trim().matches(Regex("^[.…\\s]+$"))

    private fun firstString(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun readOptions(o: JSONObject): List<String> {
        // Preferred format: fixed keys "A".."D" (a 1B model can't run away the way it does with an array).
        val keyed = listOf("A", "B", "C", "D").map { k -> o.optString(k, o.optString(k.lowercase(), "")) }
        if (keyed.all { it.isNotBlank() }) return keyed.map { it.replace(optionPrefix, "").trim() }
        val v = o.opt("options") ?: o.opt("choices") ?: return emptyList()
        val list = when (v) {
            is JSONArray -> (0 until v.length()).map { v.optString(it, "") }
            is JSONObject -> v.keys().asSequence().sorted().map { v.optString(it, "") }.toList()
            else -> emptyList()
        }
        return list.map { it.replace(optionPrefix, "").trim() }
    }

    private fun readAnswer(o: JSONObject, options: List<String>): Int {
        val v = o.opt("answer") ?: o.opt("answer_index") ?: o.opt("correct") ?: return -1
        return when (v) {
            is Number -> {
                val n = v.toInt()
                if (n in 0..3) n else if (n == 4) 3 else -1
            }
            is String -> {
                val s = v.trim()
                s.toIntOrNull()?.let { n -> return if (n in 0..3) n else if (n == 4) 3 else -1 }
                if (s.length == 1 && s[0].uppercaseChar() in 'A'..'D') return s[0].uppercaseChar() - 'A'
                val letter = Regex("^\\(?([A-Da-d])[).:]").find(s)?.groupValues?.get(1)
                if (letter != null) return letter[0].uppercaseChar() - 'A'
                val ns = QuestionDeduper.normalize(s)
                options.indexOfFirst { QuestionDeduper.normalize(it) == ns }
            }
            else -> -1
        }
    }
}

/** [needs] = 1-based numbers of earlier steps this one builds on (proposed by the model, validated later). */
data class RoadmapDraft(val title: String, val goal: String, val subtopics: List<String>, val needs: List<Int> = emptyList())

object RoadmapParser {
    private val numbered = Regex("^\\s*(?:step\\s*)?(\\d{1,2})\\s*[.):-]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val fieldSep = Regex("\\s+[-–—|]\\s+")
    private val subLabel = Regex("^\\s*sub\\s*-?\\s*topics?\\s*:?\\s*", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<RoadmapDraft>? = parseLines(raw) ?: parseJson(raw)

    /** "1. Title - goal - Subtopics: a, b, c" lines (the format the app asks for). */
    fun parseLines(raw: String): List<RoadmapDraft>? {
        val steps = raw.lines().mapNotNull { line ->
            val m = numbered.find(line) ?: return@mapNotNull null
            // The model often repeats the number after the "1. " prefill ("1. 1. Title"), so strip it again.
            val body = m.groupValues[2].replace("**", "").replace(Regex("^(\\s*\\d{1,2}\\s*[.):]\\s*)+"), "")
            val all = body.split(fieldSep).map { it.trim() }.filter { it.isNotEmpty() }
            // Optional "Needs: 1, 2" / "Needs: none" field with prerequisite step numbers.
            val needsPart = all.drop(1).firstOrNull { needsLabel.containsMatchIn(it) }
            val needs = needsPart?.replace(needsLabel, "")?.let { n -> Regex("\\d{1,2}").findAll(n).map { it.value.toInt() }.toList() }.orEmpty()
            val parts = all.filter { it !== needsPart }
            if (parts.isEmpty()) return@mapNotNull null
            val title = parts[0].removeSuffix(":").trim()
            if (title.length < 3) return@mapNotNull null
            // "Title - goal - Subtopics: a, b" or, when the label is dropped, "Title - goal - a, b".
            val subPart = parts.drop(1).firstOrNull { subLabel.containsMatchIn(it) }
                ?: parts.getOrNull(2)
                ?: parts.getOrNull(1)?.takeIf { p -> p.count { it == ',' } >= 2 && !p.trimEnd().endsWith('.') }
            val goal = parts.drop(1).firstOrNull { it !== subPart && !subLabel.containsMatchIn(it) }.orEmpty()
            val subs = subPart?.replace(subLabel, "")?.split(',', ';')
                ?.map { it.trim().trimEnd('.').replace(Regex("^(and|or|&)\\s+", RegexOption.IGNORE_CASE), "") }
                ?.filter { it.isNotEmpty() }?.distinct()?.take(4).orEmpty()
            RoadmapDraft(title.take(80), goal.take(200), subs.ifEmpty { listOf(title) }, needs)
        }.distinctBy { QuestionDeduper.normalize(it.title) }
        return steps.takeIf { it.size >= 3 }?.take(6)
    }

    private val needsLabel = Regex("^\\s*(needs|requires|prerequisites?|builds on)\\s*:?\\s*", RegexOption.IGNORE_CASE)

    /** (step number, prerequisite step number) pairs, both 1-based, as proposed by the model. */
    fun proposedEdges(drafts: List<RoadmapDraft>): List<Pair<Int, Int>> =
        drafts.flatMapIndexed { i, d -> d.needs.map { (i + 1) to it } }

    fun parseJson(raw: String): List<RoadmapDraft>? {
        val json = JsonRepair.extractObject(raw) ?: return null
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val arr = o.optJSONArray("steps") ?: o.optJSONArray("roadmap") ?: return null
        val steps = (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = s.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            // Preferred: one comma-separated string (nested arrays confused the 1B model on-device).
            val raw = s.optJSONArray("subtopics")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: s.optString("subtopics", "").split(',', ';')
            val subs = raw.map { it.replace(Regex("^\\s*\\d+(\\.\\d+)*[.)]?\\s*"), "").trim() }
                .filter { it.isNotEmpty() }.distinct().take(5)
            RoadmapDraft(title.take(80), s.optString("goal", s.optString("description")).trim().take(200), subs.ifEmpty { listOf(title) })
        }.distinctBy { QuestionDeduper.normalize(it.title) }
        return steps.takeIf { it.size >= 3 }
    }
}
