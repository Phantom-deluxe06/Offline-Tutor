package com.offlinetutor.domain

import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

/**
 * 2a: Kotlin-owned problem templates. Kotlin picks the numbers, computes the answer and builds the wrong options
 * from known error patterns, each tagged with its misconception.
 *
 * 2-fix-1 (placeholder stories): the LLM writes only the setting, with placeholders {A}, {B}, … and no digits or
 * number words; Kotlin substitutes the real quantities and appends its own [question] sentence. So an accepted
 * story can neither change the numbers nor change what is asked. Otherwise [defaultStory] is used.
 */
enum class TemplateType { SPEED, PERCENT, SIMPLE_INTEREST, AREA_PERIMETER, OHMS_LAW, DENSITY, UNIT_CONVERSION, AVERAGE }

/** One quantity the story must mention, as placeholder {A}, {B}, … in slot order. */
data class Slot(val value: String, val meaning: String)

data class TemplateProblem(
    val type: TemplateType,
    /** Quantities the story must place, in placeholder order ({A} = slots[0], …). */
    val slots: List<Slot>,
    /** Kotlin-owned final question sentence, already with any numbers it needs. */
    val question: String,
    /** Suggested local setting for the LLM story. */
    val setting: String,
    /** Kotlin's own story, with placeholders. */
    val defaultStory: String,
    val answer: String,
    val wrong: List<Pair<String, OptionTag>>,
    val explanation: String,
    val expression: String,
) {
    val givens: List<String> get() = slots.map { it.value }
    val placeholders: List<String> get() = slots.indices.map { ProblemTemplates.placeholder(it) }

    /** Replaces {A}, {B}, … in a story with the real quantities. */
    fun fill(story: String): String = slots.foldIndexed(story) { i, s, slot -> s.replace(ProblemTemplates.placeholder(i), slot.value) }

    /** Full question text for an (accepted, placeholder) story: filled story + Kotlin's question. */
    fun textFor(story: String): String = "${fill(story).trim()} $question"

    val defaultText: String get() = textFor(defaultStory)

    fun toDiag(concept: String, question: String = defaultText): DiagMcq = DiagMcq(
        question = question, concept = concept, difficulty = null, explanation = explanation, expression = expression,
        options = listOf(DiagOption(answer, true)) + wrong.map { (t, tag) -> DiagOption(t, false, tag.code, tag.why) },
    )
}

object ProblemTemplates {
    fun placeholder(i: Int) = "{${'A' + i}}"

    // Word-boundary patterns so "rural area", "current affairs", "unity" or "resistance movement" don't match.
    private val keywords = listOf(
        TemplateType.SPEED to listOf("\\bspeed", "\\bdistance\\b", "\\bvelocity", "\\bmotion\\b"),
        TemplateType.SIMPLE_INTEREST to listOf("simple interest", "interest rate", "\\bbank interest"),
        TemplateType.PERCENT to listOf("\\bpercent", "\\bdiscount", "\\bprofit"),
        TemplateType.AREA_PERIMETER to listOf("\\barea of\\b", "\\bperimeter", "\\bmensuration", "\\brectangle"),
        TemplateType.OHMS_LAW to listOf("\\bohm", "electric current", "\\belectricity\\b", "\\bcircuit", "\\bvoltage"),
        TemplateType.DENSITY to listOf("\\bdensity", "mass and volume"),
        TemplateType.UNIT_CONVERSION to listOf("\\bunits?\\b", "unit conversion", "\\bmeasurement", "\\bmetric\\b"),
        TemplateType.AVERAGE to listOf("\\baverage", "\\bmean\\b", "data handling", "\\bstatistics"),
    ).map { (t, ps) -> t to ps.map { Regex(it, RegexOption.IGNORE_CASE) } }

    /** Template matching a concept / subtopic text in a Maths or Science course, or null. */
    fun forTopic(text: String, course: String = "Mathematics"): TemplateType? {
        val subject = Subjects.canonical(course)
        val stem = course.lowercase()
        if (subject != "Mathematics" && subject != "Science" && "physic" !in stem && "chem" !in stem) return null
        return keywords.firstOrNull { (_, patterns) -> patterns.any { it.containsMatchIn(text) } }?.first
    }

    /** True when [text] looks like an arithmetic/numeric topic that templates should own (2-fix-2). */
    fun isNumericTopic(text: String, course: String): Boolean = forTopic(text, course) != null

    // ---------- 2-fix-1: story acceptance ----------

    private val numberWords = Regex(
        "\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|" +
            "seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundreds?|thousands?|" +
            "lakhs?|crores?|millions?|billions?|once|twice|thrice|half|halves|quarters?|double|triple|dozens?|" +
            "first|second|third|fourth|fifth|tenth)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val anyPlaceholder = Regex("\\{[^}]*\\}")
    private val opener = Regex("^(here is|here's|here are|sure|okay|ok)\\b", RegexOption.IGNORE_CASE)
    private val inlinePreamble = Regex("^(here is |here's )?(a |the )?(short )?(word )?(problem|setting|story)\\s*:\\s*", RegexOption.IGNORE_CASE)

    /** Why a story was rejected, or null if accepted (also used for logging). */
    fun storyProblem(story: String?, slotCount: Int): String? {
        if (story == null) return "no usable text"
        val found = anyPlaceholder.findAll(story).map { it.value }.toList()
        for (i in 0 until slotCount) {
            val n = found.count { it == placeholder(i) }
            if (n != 1) return "placeholder ${placeholder(i)} appears $n times"
        }
        found.firstOrNull { f -> (0 until slotCount).none { placeholder(it) == f } }?.let { return "unknown placeholder $it" }
        if (story.any { it.isDigit() }) return "contains a digit"
        numberWords.find(story)?.let { return "contains the number word \"${it.value}\"" }
        return null
    }

    /**
     * Cleans the model's story (drops "Here is…" openers on their own line or inline, labels, quotes, anything after
     * an "Answer"/"Question" label, and any question sentence the model added) and returns it with placeholders, or
     * null if nothing usable is left.
     */
    fun cleanStory(raw: String): String? {
        var t = raw.trim()
        // Opener on its own line ("Here is a short setting for a Grade 8 student:"), possibly followed by a blank line.
        while ('\n' in t) {
            val first = t.substringBefore('\n').trim()
            if (first.endsWith(':') || (opener.containsMatchIn(first) && first.length < 90)) t = t.substringAfter('\n').trim() else break
        }
        t = t.replace(Regex("^(sure|okay|ok)[!.,]?\\s+", RegexOption.IGNORE_CASE), "")
            .replace(inlinePreamble, "")
            .substringBefore("\nAnswer").substringBefore("Answer:").substringBefore("Question:").substringBefore("Solution")
            .replace("\n", " ").replace(Regex("\\s+"), " ")
            .trim().trim('"', '“', '”', '*').trim()
        // Kotlin owns the question: drop any sentence the model ended with '?'.
        val sentences = Regex("[^.!?]+[.!?]?").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        t = sentences.filterNot { it.endsWith("?") }.joinToString(" ").trim()
        if (t.length < 15) return null
        if (!t.endsWith('.') && !t.endsWith('!')) t = "$t."
        return t
    }

    private val unitWords = mapOf("rs" to setOf("rs", "rupee", "rupees", "₹"), "%" to setOf("percent", "%"))

    /**
     * After filling, a unit must not appear twice around a placeholder ("{A} rupees" with {A} = "Rs 400" →
     * "Rs 400 rupees"; "for {C} years" with {C} = "2 years"). Returns the problem or null.
     */
    fun unitClash(story: String, slots: List<Slot>): String? {
        slots.forEachIndexed { i, slot ->
            val ph = Regex.escape(placeholder(i))
            // Unit words of the value: "Rs 400" → {rs}; "2 years" → {years}; "5% per year" → {%, year}.
            val units = slot.value.lowercase().split(Regex("\\s+"))
                .flatMap { w -> if (w.any { it.isDigit() }) listOfNotNull(if (w.endsWith("%")) "%" else null) else listOf(w.trimEnd('.', ',')) }
                .filter { it.isNotBlank() && it != "per" }.toSet()
            val after = Regex("$ph\\s+([\\p{L}%₹]+)").find(story)?.groupValues?.get(1)?.lowercase()
            val before = Regex("([\\p{L}%₹]+)\\s+$ph").find(story)?.groupValues?.get(1)?.lowercase()
            fun clashes(a: String?): Boolean = a != null && units.any { u ->
                a.removeSuffix("s") == u.removeSuffix("s") || a in (unitWords[u] ?: emptySet())
            }
            if (clashes(after)) return "unit repeated after ${placeholder(i)}"
            if (clashes(before)) return "unit repeated before ${placeholder(i)}"
        }
        return null
    }

    /** Accepted story (with placeholders) or null. */
    fun acceptStory(raw: String, slotCount: Int): String? {
        val s = cleanStory(raw)
        return if (storyProblem(s, slotCount) == null) s else null
    }

    fun fmt(v: Double): String {
        val r = round(v * 100) / 100
        return if (abs(r - round(r)) < 1e-9) round(r).toLong().toString() else r.toString().trimEnd('0').trimEnd('.')
    }

    fun generate(type: TemplateType, level: Int, rnd: Random = Random.Default): TemplateProblem {
        repeat(20) {
            val p = build(type, level.coerceIn(1, 3), rnd)
            val all = listOf(p.answer) + p.wrong.map { it.first }
            // Options must be distinct and positive; otherwise draw new numbers.
            if (all.toSet().size == 4 && all.none { it.startsWith("-") || it.startsWith("0 ") || it == "0" }) return p
        }
        return build(type, 1, Random(7))
    }

    private fun <T> Random.pick(xs: List<T>) = xs[nextInt(xs.size)]
    private fun tag(code: Misconception, why: String) = OptionTag(code, why)

    private fun build(type: TemplateType, level: Int, rnd: Random): TemplateProblem = when (type) {
        TemplateType.SPEED -> {
            val setting = rnd.pick(listOf("an auto-rickshaw ride", "a train journey", "a bus trip", "a cyclist going to school"))
            if (level < 3) {
                val t = if (level == 1) rnd.pick(listOf(2.0, 3.0, 4.0, 5.0)) else rnd.pick(listOf(1.5, 2.5, 0.5))
                val v = rnd.pick(listOf(20.0, 30.0, 40.0, 50.0, 60.0, 80.0))
                val d = v * t
                TemplateProblem(type, listOf(Slot("${fmt(d)} km", "the distance travelled"), Slot("${fmt(t)} hours", "the time taken")),
                    "What is the speed in km/h?", setting, "A vehicle covers {A} in {B}.",
                    "${fmt(v)} km/h",
                    listOf(
                        "${fmt(d * t)} km/h" to tag(Misconception.FORMULA_SWAP, "multiplied distance by time instead of dividing"),
                        "${fmt(d + t)} km/h" to tag(Misconception.OPERATION_SWAP, "added distance and time"),
                        "${fmt(d - t)} km/h" to tag(Misconception.OPERATION_SWAP, "subtracted time from distance"),
                    ),
                    "Speed = distance / time = ${fmt(d)} / ${fmt(t)} = ${fmt(v)} km/h.", "${fmt(d)}/${fmt(t)}")
            } else {
                val mins = rnd.pick(listOf(30.0, 45.0, 90.0))
                val v = rnd.pick(listOf(40.0, 60.0, 80.0))
                val d = v * mins / 60
                TemplateProblem(type, listOf(Slot("${fmt(d)} km", "the distance travelled"), Slot("${fmt(mins)} minutes", "the time taken")),
                    "What is the speed in km/h?", setting, "A vehicle covers {A} in {B}.",
                    "${fmt(v)} km/h",
                    listOf(
                        "${fmt(d / mins)} km/h" to tag(Misconception.UNIT_CONVERSION, "divided by minutes without converting to hours"),
                        "${fmt(d * mins / 60)} km/h" to tag(Misconception.FORMULA_SWAP, "multiplied distance by time in hours"),
                        "${fmt(d * 60)} km/h" to tag(Misconception.PARTIAL_STEP, "multiplied by 60 but did not divide by the minutes"),
                    ),
                    "${fmt(mins)} minutes = ${fmt(mins / 60)} hours, so speed = ${fmt(d)} / ${fmt(mins / 60)} = ${fmt(v)} km/h.",
                    "${fmt(d)}/(${fmt(mins)}/60)")
            }
        }
        TemplateType.PERCENT -> {
            val setting = rnd.pick(listOf("a shop sale at Diwali", "a school fee", "a cricket bat price", "a vegetable market"))
            if (level < 3) {
                val p = if (level == 1) rnd.pick(listOf(10.0, 20.0, 25.0, 50.0)) else rnd.pick(listOf(12.0, 15.0, 35.0, 40.0))
                val n = (rnd.nextInt(2, 11) * 40).toDouble()
                val ans = p * n / 100
                TemplateProblem(type, listOf(Slot("Rs ${fmt(n)}", "the amount of money")),
                    "What is ${fmt(p)}% of this amount?", setting, "An item costs {A}.", "Rs ${fmt(ans)}",
                    listOf(
                        "Rs ${fmt(p * n)}" to tag(Misconception.UNIT_CONVERSION, "forgot to divide by 100"),
                        "Rs ${fmt(n / p)}" to tag(Misconception.FORMULA_SWAP, "divided the amount by the percent"),
                        "Rs ${fmt(n - p)}" to tag(Misconception.OPERATION_SWAP, "subtracted the percent from the amount"),
                    ),
                    "${fmt(p)}% of ${fmt(n)} = ${fmt(p)} / 100 x ${fmt(n)} = ${fmt(ans)}.", "${fmt(p)}/100*${fmt(n)}")
            } else {
                val p = rnd.pick(listOf(10.0, 20.0, 25.0))
                val n = (rnd.nextInt(3, 13) * 100).toDouble()
                val inc = p * n / 100
                TemplateProblem(type, listOf(Slot("Rs ${fmt(n)}", "the old price"), Slot("${fmt(p)}%", "the percentage increase")),
                    "What is the new price?", setting, "A price of {A} goes up by {B}.", "Rs ${fmt(n + inc)}",
                    listOf(
                        "Rs ${fmt(inc)}" to tag(Misconception.PARTIAL_STEP, "found the increase but did not add it"),
                        "Rs ${fmt(n - inc)}" to tag(Misconception.SIGN_ERROR, "subtracted the increase instead of adding"),
                        "Rs ${fmt(n + p)}" to tag(Misconception.UNIT_CONVERSION, "added the percent as rupees"),
                    ),
                    "Increase = ${fmt(p)}% of ${fmt(n)} = ${fmt(inc)}; new price = ${fmt(n)} + ${fmt(inc)} = ${fmt(n + inc)}.",
                    "${fmt(n)}+${fmt(p)}/100*${fmt(n)}")
            }
        }
        TemplateType.SIMPLE_INTEREST -> {
            val pr = (rnd.nextInt(2, 21) * 500).toDouble()
            val r = if (level == 1) rnd.pick(listOf(5.0, 10.0)) else rnd.pick(listOf(4.0, 6.0, 8.0, 12.0))
            val t = if (level < 3) rnd.pick(listOf(2.0, 3.0, 4.0)) else rnd.pick(listOf(1.5, 2.5))
            val si = pr * r * t / 100
            TemplateProblem(type,
                listOf(Slot("Rs ${fmt(pr)}", "the money deposited"), Slot("${fmt(r)}% per year", "the interest rate"), Slot("${fmt(t)} years", "the time")),
                "What is the simple interest earned?",
                rnd.pick(listOf("a post office savings scheme", "a bank deposit for a farmer", "a loan for a sewing machine")),
                "Someone deposits {A} at {B} for {C}.", "Rs ${fmt(si)}",
                listOf(
                    "Rs ${fmt(pr * r * t)}" to tag(Misconception.UNIT_CONVERSION, "forgot to divide by 100"),
                    "Rs ${fmt(pr * r / 100)}" to tag(Misconception.PARTIAL_STEP, "found one year's interest only"),
                    "Rs ${fmt(pr + si)}" to tag(Misconception.MISREAD_QUESTION, "gave the total amount, not the interest"),
                ),
                "SI = P x R x T / 100 = ${fmt(pr)} x ${fmt(r)} x ${fmt(t)} / 100 = ${fmt(si)}.", "${fmt(pr)}*${fmt(r)}*${fmt(t)}/100")
        }
        TemplateType.AREA_PERIMETER -> {
            val l = rnd.nextInt(4, 21).toDouble()
            val w = rnd.nextInt(2, l.toInt()).toDouble()
            val setting = rnd.pick(listOf("a classroom floor", "a farm field", "a kitchen garden", "a rangoli border"))
            val slots = listOf(Slot("${fmt(l)} m", "the length"), Slot("${fmt(w)} m", "the width"))
            if (level == 2) {
                TemplateProblem(type, slots, "What is its perimeter in metres?", setting,
                    "A rectangle is {A} long and {B} wide.", "${fmt(2 * (l + w))} m",
                    listOf(
                        "${fmt(l * w)} m" to tag(Misconception.CONCEPT_CONFUSION, "found the area instead of the perimeter"),
                        "${fmt(l + w)} m" to tag(Misconception.PARTIAL_STEP, "added only two sides"),
                        "${fmt(4 * l)} m" to tag(Misconception.OVERGENERALISATION, "used the square formula 4 x side"),
                    ),
                    "Perimeter = 2 x (${fmt(l)} + ${fmt(w)}) = ${fmt(2 * (l + w))} m.", "2*(${fmt(l)}+${fmt(w)})")
            } else {
                TemplateProblem(type, slots, "What is its area in square metres?", setting,
                    "A rectangle is {A} long and {B} wide.", "${fmt(l * w)} sq m",
                    listOf(
                        "${fmt(2 * (l + w))} sq m" to tag(Misconception.CONCEPT_CONFUSION, "found the perimeter instead of the area"),
                        "${fmt(l + w)} sq m" to tag(Misconception.OPERATION_SWAP, "added length and width"),
                        "${fmt(l * w / 2)} sq m" to tag(Misconception.FORMULA_SWAP, "used the triangle formula (half of l x w)"),
                    ),
                    "Area = length x width = ${fmt(l)} x ${fmt(w)} = ${fmt(l * w)} sq m.", "${fmt(l)}*${fmt(w)}")
            }
        }
        TemplateType.OHMS_LAW -> {
            val i = if (level == 1) rnd.pick(listOf(1.0, 2.0, 3.0, 4.0)) else rnd.pick(listOf(0.5, 1.5, 2.5))
            val r = rnd.pick(listOf(2.0, 4.0, 5.0, 10.0, 20.0))
            val v = i * r
            TemplateProblem(type, listOf(Slot("${fmt(v)} V", "the voltage of the supply"), Slot("${fmt(r)} ohm", "the resistance")),
                "What current flows through it, in amperes?",
                rnd.pick(listOf("a torch bulb", "a mobile charger circuit", "a ceiling fan regulator")),
                "A {A} supply is connected across a {B} resistor.", "${fmt(i)} A",
                listOf(
                    "${fmt(v * r)} A" to tag(Misconception.FORMULA_SWAP, "multiplied voltage and resistance"),
                    "${fmt(r / v)} A" to tag(Misconception.FORMULA_SWAP, "divided resistance by voltage"),
                    "${fmt(v + r)} A" to tag(Misconception.OPERATION_SWAP, "added voltage and resistance"),
                ),
                "I = V / R = ${fmt(v)} / ${fmt(r)} = ${fmt(i)} A.", "${fmt(v)}/${fmt(r)}")
        }
        TemplateType.DENSITY -> {
            val d = rnd.pick(listOf(2.0, 4.0, 5.0, 8.0, 0.5))
            val vol = rnd.pick(listOf(10.0, 20.0, 25.0, 40.0))
            val m = d * vol
            TemplateProblem(type, listOf(Slot("${fmt(m)} g", "the mass"), Slot("${fmt(vol)} cm3", "the volume")),
                "What is its density in g/cm3?",
                rnd.pick(listOf("a piece of metal", "a block of soap", "a stone from a river")),
                "An object has a mass of {A} and a volume of {B}.", "${fmt(d)} g/cm3",
                listOf(
                    "${fmt(m * vol)} g/cm3" to tag(Misconception.FORMULA_SWAP, "multiplied mass and volume"),
                    "${fmt(vol / m)} g/cm3" to tag(Misconception.FORMULA_SWAP, "divided volume by mass"),
                    "${fmt(m - vol)} g/cm3" to tag(Misconception.OPERATION_SWAP, "subtracted volume from mass"),
                ),
                "Density = mass / volume = ${fmt(m)} / ${fmt(vol)} = ${fmt(d)} g/cm3.", "${fmt(m)}/${fmt(vol)}")
        }
        TemplateType.UNIT_CONVERSION -> {
            data class U(val from: String, val to: String, val factor: Double, val setting: String, val meaning: String)
            val u = rnd.pick(listOf(
                U("km", "m", 1000.0, "a walk to the market", "the distance walked"),
                U("kg", "g", 1000.0, "a bag of rice", "the weight of the bag"),
                U("litres", "mL", 1000.0, "a bottle of milk", "the amount of milk"),
                U("m", "cm", 100.0, "a length of cloth", "the length of the cloth"),
            ))
            val x = if (level == 1) rnd.nextInt(2, 10).toDouble() else rnd.pick(listOf(1.5, 2.5, 3.25, 0.75))
            val wrongFactor = if (u.factor == 1000.0) 100.0 else 1000.0
            TemplateProblem(type, listOf(Slot("${fmt(x)} ${u.from}", u.meaning)), "How many ${u.to} is that?", u.setting,
                "The amount is {A}.", "${fmt(x * u.factor)} ${u.to}",
                listOf(
                    "${fmt(x * wrongFactor)} ${u.to}" to tag(Misconception.UNIT_CONVERSION, "used ${fmt(wrongFactor)} instead of ${fmt(u.factor)}"),
                    "${fmt(x / u.factor)} ${u.to}" to tag(Misconception.OPERATION_SWAP, "divided instead of multiplying"),
                    "${fmt(x * 10)} ${u.to}" to tag(Misconception.UNIT_CONVERSION, "multiplied by 10 only"),
                ),
                "1 ${u.from} = ${fmt(u.factor)} ${u.to}, so ${fmt(x)} ${u.from} = ${fmt(x * u.factor)} ${u.to}.", "${fmt(x)}*${fmt(u.factor)}")
        }
        TemplateType.AVERAGE -> {
            val n = if (level == 1) 3 else if (level == 2) 4 else 5
            val xs = MutableList(n) { rnd.nextInt(10, 90) }
            val fix = xs.sum() % n
            xs[n - 1] = xs[n - 1] + (if (fix == 0) 0 else n - fix)
            val sum = xs.sum().toDouble()
            val mean = sum / n
            val sorted = xs.sorted()
            val middle = sorted[n / 2].toDouble()
            val list = xs.dropLast(1).joinToString(", ") + " and " + xs.last() + " runs"
            // One placeholder for the whole score list: the 1B model placed separate per-score placeholders poorly.
            TemplateProblem(type, listOf(Slot(list, "the list of scores")),
                "What is the average score per match over these $n matches?",
                "a batter's cricket scores", "A batter scored {A} in recent matches.", fmt(mean),
                listOf(
                    fmt(sum) to tag(Misconception.PARTIAL_STEP, "added the scores but did not divide"),
                    fmt(sum / (n - 1)) to tag(Misconception.MISREAD_QUESTION, "divided by one less than the number of matches"),
                    fmt(if (middle == mean) middle + 1 else middle) to tag(Misconception.CONCEPT_CONFUSION, "took the middle score instead of the average"),
                ),
                "Average = (${xs.joinToString(" + ")}) / $n = ${fmt(sum)} / $n = ${fmt(mean)}.", "(${xs.joinToString("+")})/$n")
        }
    }

    /** The final text must contain every given quantity exactly (kept as a safety net after substitution). */
    fun textKeepsNumbers(text: String, givens: List<String>): Boolean {
        val norm = text.replace(Regex("\\s+"), " ")
        return givens.all { g ->
            Regex("-?\\d+(?:\\.\\d+)?").findAll(g).map { it.value }.toList().ifEmpty { return@all norm.contains(g) }.all { num ->
                Regex("(?<![\\d.])" + Regex.escape(num) + "(?![\\d.]*\\d)").containsMatchIn(norm)
            }
        }
    }

    /** Numbers in the text must all be given quantities or appear in the Kotlin question (never the answer). */
    fun noExtraNumbers(text: String, givens: List<String>): Boolean {
        val allowed = givens.flatMap { g -> Regex("\\d+(?:\\.\\d+)?").findAll(g).map { it.value }.toList() }.toSet()
        return Regex("\\d+(?:\\.\\d+)?").findAll(text).all { it.value in allowed }
    }
}
