package com.offlinetutor.domain

import android.content.Context
import org.json.JSONArray

data class SeedQuestion(
    val subject: String,
    val topic: String,
    val difficulty: Int,
    val mcq: Mcq,
)

/** Maps free-text course names onto the five seed/eval subjects. */
object Subjects {
    val EVAL = listOf("Mathematics", "Science", "English", "Social Science", "Computer Science")

    fun canonical(course: String): String? {
        val c = course.lowercase()
        return when {
            "math" in c || "algebra" in c || "geometry" in c -> "Mathematics"
            "physic" in c || "chem" in c || "bio" in c || "science" in c && "computer" !in c && "social" !in c -> "Science"
            "english" in c || "grammar" in c -> "English"
            "social" in c || "history" in c || "geograph" in c || "civic" in c || "econom" in c || "politic" in c -> "Social Science"
            "computer" in c || "coding" in c || "program" in c || "python" in c -> "Computer Science"
            else -> null
        }
    }
}

/** Small hand-checked question bank used when the model's MCQ fails validation twice. */
class SeedBank(context: Context) {
    val all: List<SeedQuestion> by lazy {
        val text = context.assets.open("seed_questions.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val opts = o.getJSONArray("options")
            SeedQuestion(
                subject = o.getString("subject"),
                topic = o.getString("topic"),
                difficulty = o.getInt("difficulty"),
                mcq = Mcq(o.getString("question"), (0 until opts.length()).map { opts.getString(it) }, o.getInt("answer"), o.getString("explanation")),
            )
        }
    }

    /** Best unused question: same subject and difficulty, then same subject, then anything. */
    fun pick(course: String, difficulty: Int, usedNormalized: Set<String>): SeedQuestion? {
        val subject = Subjects.canonical(course)
        val unused = all.filter { QuestionDeduper.normalize(it.mcq.question) !in usedNormalized }
        return unused.filter { it.subject == subject && it.difficulty == difficulty }.randomOrNull()
            ?: unused.filter { it.subject == subject }.minByOrNull { kotlin.math.abs(it.difficulty - difficulty) }
            ?: unused.filter { it.difficulty == difficulty }.randomOrNull()
            ?: unused.randomOrNull()
    }
}

/** Offline fallback roadmaps when the model's JSON can't be parsed after a retry. */
object RoadmapTemplates {
    fun forCourse(course: String): List<RoadmapDraft> = when (Subjects.canonical(course)) {
        "Mathematics" -> listOf(
            RoadmapDraft("Number sense", "Be fluent with whole numbers, fractions and decimals.", listOf("Fractions", "Decimals", "Percentages")),
            RoadmapDraft("Algebra basics", "Use letters for numbers and solve simple equations.", listOf("Variables", "Linear equations", "Word problems")),
            RoadmapDraft("Geometry", "Know shapes, angles and their properties.", listOf("Angles", "Triangles", "Polygons")),
            RoadmapDraft("Mensuration", "Find perimeter, area and volume.", listOf("Perimeter", "Area", "Volume")),
            RoadmapDraft("Data handling", "Read and draw graphs; find averages.", listOf("Mean and median", "Bar graphs", "Probability")),
            RoadmapDraft("Problem solving", "Combine ideas to solve multi-step problems.", listOf("Profit and loss", "Speed, distance and time", "Ratio and proportion")),
        )
        "Science" -> listOf(
            RoadmapDraft("Matter around us", "Understand states and properties of matter.", listOf("States of matter", "Mixtures", "Changes of state")),
            RoadmapDraft("Living world", "Learn how living things grow and get food.", listOf("Cells", "Photosynthesis", "Human body")),
            RoadmapDraft("Force and motion", "Describe motion and the forces behind it.", listOf("Speed", "Force", "Gravity")),
            RoadmapDraft("Energy", "Explore heat, light, sound and electricity.", listOf("Heat", "Light", "Electricity")),
            RoadmapDraft("Chemical changes", "Recognise reactions, acids and bases.", listOf("Acids and bases", "Chemical reactions", "Metals and non-metals")),
            RoadmapDraft("Our environment", "Link science to nature and resources.", listOf("Ecosystems", "Water cycle", "Pollution")),
        )
        "English" -> listOf(
            RoadmapDraft("Parts of speech", "Identify nouns, verbs, adjectives and more.", listOf("Nouns", "Verbs", "Adjectives")),
            RoadmapDraft("Tenses", "Use present, past and future tenses correctly.", listOf("Simple tenses", "Continuous tenses", "Perfect tenses")),
            RoadmapDraft("Sentence building", "Write correct and varied sentences.", listOf("Subject-verb agreement", "Prepositions", "Conjunctions")),
            RoadmapDraft("Vocabulary", "Grow your word power.", listOf("Synonyms", "Antonyms", "Idioms")),
            RoadmapDraft("Transformations", "Change voice and narration.", listOf("Active and passive voice", "Reported speech")),
            RoadmapDraft("Reading and writing", "Understand passages and write clearly.", listOf("Comprehension", "Letter writing", "Figures of speech")),
        )
        "Social Science" -> listOf(
            RoadmapDraft("Our earth", "Understand maps, landforms and climate.", listOf("Maps", "Landforms", "Climate")),
            RoadmapDraft("India's geography", "Know India's rivers, soils and resources.", listOf("Rivers of India", "Soils", "Agriculture")),
            RoadmapDraft("Ancient and medieval India", "Trace early Indian history.", listOf("Harappan civilisation", "Empires", "Medieval kingdoms")),
            RoadmapDraft("Freedom struggle", "Learn how India won independence.", listOf("1857 revolt", "Gandhian movements", "Independence")),
            RoadmapDraft("Constitution and government", "Understand how India is governed.", listOf("Indian Constitution", "Fundamental rights", "Parliament")),
            RoadmapDraft("Economics basics", "Learn about money, trade and development.", listOf("Economics", "Trade", "Development")),
        )
        "Computer Science" -> listOf(
            RoadmapDraft("Computer basics", "Know the parts of a computer.", listOf("Computer basics", "Input and output", "Data units")),
            RoadmapDraft("Number systems", "Convert between binary and decimal.", listOf("Number systems", "Binary arithmetic")),
            RoadmapDraft("Thinking like a programmer", "Break problems into steps.", listOf("Algorithms", "Flowcharts")),
            RoadmapDraft("Python basics", "Write your first programs.", listOf("Python strings", "Python operators", "Variables")),
            RoadmapDraft("Control flow", "Make decisions and repeat steps.", listOf("If statements", "Loops")),
            RoadmapDraft("Data structures", "Store and organise data.", listOf("Lists", "Data structures")),
        )
        else -> listOf(
            RoadmapDraft("Foundations of $course", "Learn the key words and basic ideas.", listOf("Key terms", "Basic ideas")),
            RoadmapDraft("Core concepts", "Understand the main concepts of $course.", listOf("Main concepts", "Examples")),
            RoadmapDraft("Practice", "Apply the concepts in simple problems.", listOf("Practice questions", "Common mistakes")),
            RoadmapDraft("Applications", "See where $course is used in daily life.", listOf("Real-life uses")),
            RoadmapDraft("Revision", "Revise and test yourself.", listOf("Revision", "Mock test")),
        )
    }
}
