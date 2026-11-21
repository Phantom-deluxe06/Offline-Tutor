package com.offlinetutor.domain

import kotlin.math.abs
import kotlin.math.round
import kotlin.random.Random

/**
 * 2a: Kotlin-owned problem templates (most reliable). Kotlin picks the numbers, computes the answer and builds
 * the wrong options from known error patterns, each tagged with its misconception. The LLM only writes the
 * word-problem text around the given numbers (checked to contain them exactly); otherwise [defaultText] is used.
 */
enum class TemplateType { SPEED, PERCENT, SIMPLE_INTEREST, AREA_PERIMETER, OHMS_LAW, DENSITY, UNIT_CONVERSION, AVERAGE }

data class TemplateProblem(
    val type: TemplateType,
    /** Quantities the word problem must state, exactly as written here (e.g. "120 km", "1.5 hours"). */
    val givens: List<String>,
    /** What the question asks for, e.g. "the speed of the bus in km/h". */
    val ask: String,
    /** Suggested local setting for the LLM text. */
    val setting: String,
    val defaultText: String,
    val answer: String,
    val wrong: List<Pair<String, OptionTag>>,
    val explanation: String,
    val expression: String,
) {
    fun toDiag(concept: String, question: String = defaultText): DiagMcq = DiagMcq(
        question = question, concept = concept, difficulty = null, explanation = explanation, expression = expression,
        options = listOf(DiagOption(answer, true)) + wrong.map { (t, tag) -> DiagOption(t, false, tag.code, tag.why) },
    )
}

object ProblemTemplates {
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

    /** Numbers in the text must all be given quantities (no extra numbers, and never the answer). */
    fun noExtraNumbers(text: String, givens: List<String>): Boolean {
        val allowed = givens.flatMap { g -> Regex("\\d+(?:\\.\\d+)?").findAll(g).map { it.value }.toList() }.toSet()
        return Regex("\\d+(?:\\.\\d+)?").findAll(text).all { it.value in allowed }
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
                TemplateProblem(type, listOf("${fmt(d)} km", "${fmt(t)} hours"), "the speed in km/h", setting,
                    "A vehicle covers ${fmt(d)} km in ${fmt(t)} hours. What is its speed in km/h?",
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
                TemplateProblem(type, listOf("${fmt(d)} km", "${fmt(mins)} minutes"), "the speed in km/h", setting,
                    "A vehicle covers ${fmt(d)} km in ${fmt(mins)} minutes. What is its speed in km/h?",
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
                TemplateProblem(type, listOf("${fmt(p)}%", "Rs ${fmt(n)}"), "${fmt(p)}% of the amount in rupees", setting,
                    "What is ${fmt(p)}% of Rs ${fmt(n)}?", "Rs ${fmt(ans)}",
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
                TemplateProblem(type, listOf("Rs ${fmt(n)}", "${fmt(p)}%"), "the new price in rupees after the increase", setting,
                    "A price of Rs ${fmt(n)} goes up by ${fmt(p)}%. What is the new price?", "Rs ${fmt(n + inc)}",
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
            TemplateProblem(type, listOf("Rs ${fmt(pr)}", "${fmt(r)}%", "${fmt(t)} years"), "the simple interest in rupees",
                rnd.pick(listOf("a post office savings scheme", "a bank deposit for a farmer", "a loan for a sewing machine")),
                "Find the simple interest on Rs ${fmt(pr)} at ${fmt(r)}% per year for ${fmt(t)} years.", "Rs ${fmt(si)}",
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
            if (level == 2) {
                TemplateProblem(type, listOf("${fmt(l)} m", "${fmt(w)} m"), "the perimeter in metres", setting,
                    "A rectangle is ${fmt(l)} m long and ${fmt(w)} m wide. What is its perimeter?", "${fmt(2 * (l + w))} m",
                    listOf(
                        "${fmt(l * w)} m" to tag(Misconception.CONCEPT_CONFUSION, "found the area instead of the perimeter"),
                        "${fmt(l + w)} m" to tag(Misconception.PARTIAL_STEP, "added only two sides"),
                        "${fmt(4 * l)} m" to tag(Misconception.OVERGENERALISATION, "used the square formula 4 x side"),
                    ),
                    "Perimeter = 2 x (${fmt(l)} + ${fmt(w)}) = ${fmt(2 * (l + w))} m.", "2*(${fmt(l)}+${fmt(w)})")
            } else {
                TemplateProblem(type, listOf("${fmt(l)} m", "${fmt(w)} m"), "the area in square metres", setting,
                    "A rectangle is ${fmt(l)} m long and ${fmt(w)} m wide. What is its area?", "${fmt(l * w)} sq m",
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
            TemplateProblem(type, listOf("${fmt(v)} V", "${fmt(r)} ohm"), "the current in amperes",
                rnd.pick(listOf("a torch bulb", "a mobile charger circuit", "a ceiling fan regulator")),
                "A ${fmt(v)} V supply is connected across a ${fmt(r)} ohm resistor. What current flows?", "${fmt(i)} A",
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
            TemplateProblem(type, listOf("${fmt(m)} g", "${fmt(vol)} cm3"), "the density in g/cm3",
                rnd.pick(listOf("a piece of metal", "a block of soap", "a stone from a river")),
                "An object has a mass of ${fmt(m)} g and a volume of ${fmt(vol)} cm3. What is its density?", "${fmt(d)} g/cm3",
                listOf(
                    "${fmt(m * vol)} g/cm3" to tag(Misconception.FORMULA_SWAP, "multiplied mass and volume"),
                    "${fmt(vol / m)} g/cm3" to tag(Misconception.FORMULA_SWAP, "divided volume by mass"),
                    "${fmt(m - vol)} g/cm3" to tag(Misconception.OPERATION_SWAP, "subtracted volume from mass"),
                ),
                "Density = mass / volume = ${fmt(m)} / ${fmt(vol)} = ${fmt(d)} g/cm3.", "${fmt(m)}/${fmt(vol)}")
        }
        TemplateType.UNIT_CONVERSION -> {
            data class U(val from: String, val to: String, val factor: Double, val setting: String)
            val u = rnd.pick(listOf(
                U("km", "m", 1000.0, "a walk to the market"), U("kg", "g", 1000.0, "a bag of rice"),
                U("litres", "mL", 1000.0, "a bottle of milk"), U("m", "cm", 100.0, "a length of cloth"),
            ))
            val x = if (level == 1) rnd.nextInt(2, 10).toDouble() else rnd.pick(listOf(1.5, 2.5, 3.25, 0.75))
            val wrongFactor = if (u.factor == 1000.0) 100.0 else 1000.0
            TemplateProblem(type, listOf("${fmt(x)} ${u.from}"), "the amount in ${u.to}", u.setting,
                "How many ${u.to} are there in ${fmt(x)} ${u.from}?", "${fmt(x * u.factor)} ${u.to}",
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
            TemplateProblem(type, xs.map { it.toString() } + "$n matches", "the average runs per match",
                "a batter's cricket scores in ${n} matches",
                "A batter scores ${xs.joinToString(", ")} runs in $n matches. What is the average score?", fmt(mean),
                listOf(
                    fmt(sum) to tag(Misconception.PARTIAL_STEP, "added the scores but did not divide"),
                    fmt(sum / (n - 1)) to tag(Misconception.MISREAD_QUESTION, "divided by one less than the number of matches"),
                    fmt(if (middle == mean) middle + 1 else middle) to tag(Misconception.CONCEPT_CONFUSION, "took the middle score instead of the average"),
                ),
                "Average = (${xs.joinToString(" + ")}) / $n = ${fmt(sum)} / $n = ${fmt(mean)}.", "(${xs.joinToString("+")})/$n")
        }
    }

    /** The LLM's word-problem text must contain every given quantity exactly. */
    fun textKeepsNumbers(text: String, givens: List<String>): Boolean {
        val norm = text.replace(Regex("\\s+"), " ")
        return givens.all { g ->
            val num = Regex("-?\\d+(?:\\.\\d+)?").find(g)?.value ?: return@all norm.contains(g)
            Regex("(?<![\\d.])" + Regex.escape(num) + "(?![\\d.]*\\d)").containsMatchIn(norm)
        }
    }
}
