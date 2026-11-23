package com.offlinetutor.data

import com.offlinetutor.domain.DiagnosticParser
import com.offlinetutor.domain.DiagParse
import com.offlinetutor.domain.Mcq
import com.offlinetutor.domain.McqParse
import com.offlinetutor.domain.McqParser
import com.offlinetutor.domain.OptionMeta
import com.offlinetutor.domain.PoolPolicy
import com.offlinetutor.domain.QuestionDeduper
import com.offlinetutor.domain.QuestionShare
import org.json.JSONArray
import org.json.JSONObject

/**
 * 5b distributed question cache: what this phone offers (pool items only — never attempts or profiles), the items a
 * peer asked for, and validation of received items before they enter the pool (source = "peer"):
 *  - template items are rebuilt from (type, level, seed, story): Kotlin recomputes the numbers and the answer;
 *  - LLM items go through the MCQ schema validator, the tag check and dedupe again.
 */
class QuestionCache(private val pool: PoolDao, private val quiz: QuizDao) {
    data class Received(val stored: Int, val llm: Int, val template: Int, val rejected: Int)

    suspend fun manifest(profile: Profile): List<QuestionShare.Entry> = pool.all(profile.id).map {
        QuestionShare.Entry(QuestionShare.hash(it.normalizedStem), it.concept, profile.grade, it.level)
    }

    suspend fun known(profile: Profile): Set<String> =
        (pool.all(profile.id).map { it.normalizedStem } + quiz.allNormalizedStems(profile.id)).map { QuestionShare.hash(it) }.toSet()

    /** JSON array of the requested items. */
    suspend fun itemsJson(profile: Profile, hashes: Collection<String>): String {
        val want = hashes.toSet()
        val items = pool.all(profile.id).filter { QuestionShare.hash(it.normalizedStem) in want }.map { it.toShareJson() }
        return JSONArray(items).toString()
    }

    private fun PoolItem.toShareJson(): JSONObject = JSONObject().put("concept", concept).put("course", course)
        .put("subtopic", subtopic).put("level", level).apply {
            val s = spec
            if (source == "template" && s != null) put("spec", s)
            else put("stem", stem).put("options", JSONArray(options.split(QuizQuestion.OPTION_SEP))).put("answer", answerIndex)
                .put("explanation", explanation).put("meta", optionMeta ?: JSONObject.NULL)
        }

    /** Validates and stores received items into this profile's pool. */
    suspend fun receive(profile: Profile, json: String, maxItems: Int): Received {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return Received(0, 0, 0, 0)
        val history = (quiz.allNormalizedStems(profile.id) + pool.all(profile.id).map { it.normalizedStem }).toMutableSet()
        var stored = 0; var llm = 0; var tpl = 0; var rejected = 0
        for (i in 0 until minOf(arr.length(), maxItems)) {
            val o = arr.optJSONObject(i) ?: run { rejected++; null } ?: continue
            if (pool.countForProfile(profile.id) >= PoolPolicy.PROFILE_CAP) break
            val item = if (o.has("spec")) fromTemplate(profile, o) else fromLlm(profile, o)
            if (item == null || QuestionDeduper.isDuplicate(item.stem, history)) { rejected++; continue }
            pool.insert(item)
            history.add(item.normalizedStem)
            stored++
            if (o.has("spec")) tpl++ else llm++
        }
        return Received(stored, llm, tpl, rejected)
    }

    private fun fromTemplate(profile: Profile, o: JSONObject): PoolItem? {
        val parts = o.getString("spec").split("|")
        if (parts.size < 3) return null
        val level = parts[1].toIntOrNull() ?: return null
        val seed = parts[2].toLongOrNull() ?: return null
        val (p, text) = QuestionShare.rebuildTemplate(parts[0], level, seed, parts.getOrNull(3)?.ifBlank { null }) ?: return null
        val d = p.toDiag(o.getString("concept"), text).shuffled()
        return PoolItem(
            profileId = profile.id, course = o.optString("course"), concept = o.getString("concept"), subtopic = o.optString("subtopic"),
            level = level, stem = d.question, normalizedStem = QuestionDeduper.normalize(d.question),
            options = d.options.joinToString(QuizQuestion.OPTION_SEP.toString()) { it.text }, answerIndex = d.answer,
            explanation = d.explanation, optionMeta = d.metaJson(), source = "peer", verdict = "TEMPLATE", genMs = 0,
            spec = o.getString("spec"),
        )
    }

    private fun fromLlm(profile: Profile, o: JSONObject): PoolItem? {
        val opts = o.optJSONArray("options") ?: return null
        val m = Mcq(o.optString("stem"), (0 until opts.length()).map { opts.optString(it) }, o.optInt("answer", -1), o.optString("explanation"))
        val ok = (McqParser.validate(m) as? McqParse.Ok)?.mcq ?: return null
        val meta = if (o.isNull("meta")) null else o.optString("meta")
        if (meta != null) {
            // Tags must still describe exactly the three wrong options of this key.
            val tags = OptionMeta.parse(meta)
            val wrongTags = ok.options.indices.filter { it != ok.answer }.map { tags.getOrNull(it) }
            if (DiagnosticParser.fromMcq(ok, o.getString("concept"), wrongTags) !is DiagParse.Ok) return null
        }
        return PoolItem(
            profileId = profile.id, course = o.optString("course"), concept = o.getString("concept"), subtopic = o.optString("subtopic"),
            level = o.optInt("level", 1).coerceIn(1, 3), stem = ok.question, normalizedStem = QuestionDeduper.normalize(ok.question),
            options = ok.options.joinToString(QuizQuestion.OPTION_SEP.toString()), answerIndex = ok.answer,
            explanation = ok.explanation, optionMeta = meta, source = "peer", verdict = null, genMs = 0,
        )
    }
}
