package com.offlinetutor.domain

import org.json.JSONObject

/**
 * 4b: hand-curated bank of local analogies keyed by concept area x world (assets/analogies.json), e.g.
 * fractions → dosa slices, symmetry → kolam, water cycle → monsoon, current → water in a pipe.
 * The matching hint is injected into explanation / fix / chat prompts so the model builds around it.
 */
class AnalogyBank(private val areas: List<Area>) {
    data class Area(val area: String, val keywords: List<String>, val worlds: Map<String, String>)

    /** Analogy for the first area whose keywords appear in [text], in [world] (or the area's village/kitchen one). */
    fun find(text: String, world: String?, course: String? = null): String? {
        // The bank covers maths and science areas only ("rural area" in Social Science must not match "area").
        if (course != null && Subjects.canonical(course).let { it != "Mathematics" && it != "Science" }) return null
        val t = text.lowercase()
        val a = areas.firstOrNull { ar -> ar.keywords.any { k -> Regex("\\b" + Regex.escape(k.lowercase()) + "(s|es)?\\b").containsMatchIn(t) || (k == "%" && "%" in t) } }
            ?: return null
        return world?.let { a.worlds[it] } ?: a.worlds["village"] ?: a.worlds.values.firstOrNull()
    }

    val size: Int get() = areas.size

    companion object {
        fun parse(json: String): AnalogyBank {
            val arr = JSONObject(json).getJSONArray("areas")
            return AnalogyBank((0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val kw = o.getJSONArray("keywords")
                val w = o.getJSONObject("worlds")
                Area(o.getString("area"), (0 until kw.length()).map { kw.getString(it) }, w.keys().asSequence().associateWith { w.getString(it) })
            })
        }
    }
}
