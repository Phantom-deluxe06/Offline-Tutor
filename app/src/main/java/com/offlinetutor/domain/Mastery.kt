package com.offlinetutor.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 1d: per-concept Elo-style mastery. The student's concept rating plays against the question's
 * difficulty rating; the update is weighted by confidence and response time. The next question's
 * level follows the explicit rules: fast & right → up, slow & right → stay, wrong → down; three fast
 * wrong answers in a row = frustration → easiest level + a break nudge.
 *
 * All thresholds below are tutoring design constants, not measured performance numbers.
 */
object MasteryModel {
    const val START = 1000.0
    const val K = 40.0
    const val MASTERED_AT = 1150.0
    const val MIN_ATTEMPTS_FOR_MASTERY = 3
    const val STUCK_BELOW = 1000.0
    const val STUCK_AFTER_ATTEMPTS = 4
    const val FRUSTRATION_STREAK = 3

    data class State(
        val rating: Double = START,
        val level: Int = AdaptiveDifficulty.EASY,
        val attempts: Int = 0,
        val correct: Int = 0,
        val fastWrongStreak: Int = 0,
    )

    data class Outcome(val state: State, val delta: Double, val frustrated: Boolean, val fast: Boolean)

    fun questionRating(level: Int): Double = when (level) {
        AdaptiveDifficulty.EASY -> 900.0
        AdaptiveDifficulty.MEDIUM -> 1050.0
        else -> 1200.0
    }

    fun expected(student: Double, question: Double): Double = 1.0 / (1.0 + 10.0.pow((question - student) / 400.0))

    /** Reading time scales with question length, so "fast" is relative to the number of words. */
    fun fastLimitMs(words: Int): Long = 6_000L + words * 350L

    fun isFast(responseMs: Long?, words: Int): Boolean = responseMs != null && responseMs <= fastLimitMs(words)

    /** Confident errors are strong evidence of a misconception; unsure successes are weak evidence of mastery. */
    fun weight(correct: Boolean, confident: Boolean?, fast: Boolean): Double {
        val c = when {
            confident == null -> 1.0
            correct && confident -> 1.0
            correct -> 0.6
            confident -> 1.5
            else -> 0.8
        }
        val t = if (correct) (if (fast) 1.2 else 0.8) else 1.0
        return c * t
    }

    fun update(s: State, questionLevel: Int, correct: Boolean, confident: Boolean?, responseMs: Long?, words: Int): Outcome {
        val fast = isFast(responseMs, words)
        val e = expected(s.rating, questionRating(questionLevel))
        val delta = K * weight(correct, confident, fast) * ((if (correct) 1.0 else 0.0) - e)
        var streak = if (!correct && fast) s.fastWrongStreak + 1 else 0
        var level = when {
            correct && fast -> min(AdaptiveDifficulty.HARD, questionLevel + 1)
            correct -> questionLevel
            else -> max(AdaptiveDifficulty.EASY, questionLevel - 1)
        }
        val frustrated = streak >= FRUSTRATION_STREAK
        if (frustrated) { level = AdaptiveDifficulty.EASY; streak = 0 }
        return Outcome(
            State(s.rating + delta, level, s.attempts + 1, s.correct + if (correct) 1 else 0, streak),
            delta, frustrated, fast,
        )
    }

    fun isMastered(s: State) = s.attempts >= MIN_ATTEMPTS_FOR_MASTERY && s.rating >= MASTERED_AT
    fun isStuck(s: State) = s.attempts >= STUCK_AFTER_ATTEMPTS && s.rating < STUCK_BELOW
}

/** 1e: when to insert a MICRO_FIX step. */
object HealingPolicy {
    data class Hit(val code: String, val confident: Boolean?, val resolved: Boolean)

    /**
     * Insert a fix when the student is confidently wrong once, or hits the same unresolved code twice,
     * unless an open fix for that code already exists on this concept.
     */
    fun shouldInsertFix(history: List<Hit>, newHit: Hit, openFixCodes: Set<String>): Boolean {
        if (newHit.code in openFixCodes) return false
        if (newHit.confident == true) return true
        val sameOpen = history.count { !it.resolved && it.code == newHit.code } + 1
        return sameOpen >= 2
    }
}

/**
 * 1f: prerequisite DAG over concept ids. Edges proposed by the model are validated: unknown nodes,
 * self-loops, duplicates and edges that would close a cycle are dropped.
 */
class PrereqGraph private constructor(val nodes: List<String>, private val prereqMap: Map<String, Set<String>>) {

    data class Built(val graph: PrereqGraph, val dropped: List<Pair<Pair<String, String>, String>>)

    fun prereqs(node: String): Set<String> = prereqMap[node].orEmpty()

    fun dependents(node: String): Set<String> = nodes.filter { node in prereqs(it) }.toSet()

    /** All ancestors, nearest first (breadth-first over prerequisite edges). */
    fun ancestors(node: String): List<String> {
        val out = LinkedHashSet<String>()
        var frontier = prereqs(node).toList()
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (p in frontier) if (out.add(p)) next.addAll(prereqs(p))
            frontier = next
        }
        return out.toList()
    }

    /** Longest prerequisite chain below the node (roots = 0), used as the skill-tree row. */
    fun depth(node: String): Int {
        val memo = HashMap<String, Int>()
        fun d(n: String): Int = memo.getOrPut(n) { prereqs(n).maxOfOrNull { d(it) + 1 } ?: 0 }
        return d(node)
    }

    val edgeCount: Int get() = prereqMap.values.sumOf { it.size }

    companion object {
        /** [proposed] pairs are (node, prerequisite). */
        fun build(nodes: List<String>, proposed: List<Pair<String, String>>): Built {
            val known = nodes.toSet()
            val map = nodes.associateWith { LinkedHashSet<String>() }
            val dropped = ArrayList<Pair<Pair<String, String>, String>>()
            fun reaches(from: String, target: String): Boolean {
                val seen = HashSet<String>()
                val stack = ArrayDeque(listOf(from))
                while (stack.isNotEmpty()) {
                    val n = stack.removeLast()
                    if (n == target) return true
                    if (seen.add(n)) stack.addAll(map[n].orEmpty())
                }
                return false
            }
            for (e in proposed) {
                val (node, pre) = e
                val reason = when {
                    node !in known || pre !in known -> "unknown step"
                    node == pre -> "self loop"
                    pre in map.getValue(node) -> "duplicate"
                    reaches(pre, node) -> "creates a cycle"
                    else -> null
                }
                if (reason != null) dropped.add(e to reason) else map.getValue(node).add(pre)
            }
            return Built(PrereqGraph(nodes, map), dropped)
        }

        /** Fallback when the model proposes no usable edges: each step needs the one before it. */
        fun linear(nodes: List<String>): PrereqGraph =
            build(nodes, nodes.zipWithNext { a, b -> b to a }).graph
    }
}

/** 1f: skill-tree node state. */
enum class NodeState { LOCKED, ACTIVE, MASTERED, FIX, REVIEW }

object SkillTreeRules {
    /**
     * FIX / REVIEW while an open repair step; MASTERED when done or mastered; ACTIVE when every prerequisite is
     * done or mastered; otherwise LOCKED.
     */
    fun state(kind: String?, done: Boolean, mastered: Boolean, prereqsSatisfied: Boolean): NodeState = when {
        kind == "MICRO_FIX" && !done -> NodeState.FIX
        kind == "REVIEW" && !done -> NodeState.REVIEW
        done || mastered -> NodeState.MASTERED
        prereqsSatisfied -> NodeState.ACTIVE
        else -> NodeState.LOCKED
    }
}

/** 1f: what to do when a concept stays below threshold. */
sealed interface Backtrack {
    data object None : Backtrack
    data class Probe(val concept: String) : Backtrack
    data class Review(val concept: String) : Backtrack
}

object BacktrackPlanner {
    /**
     * Walk the stuck concept's prerequisites (nearest first). An untested prerequisite gets probe
     * questions first; otherwise review the weakest one that is not yet mastered.
     */
    fun decide(
        stuck: String,
        graph: PrereqGraph,
        mastery: Map<String, MasteryModel.State>,
        alreadyHandled: Set<String> = emptySet(),
    ): Backtrack {
        val candidates = graph.ancestors(stuck).filter { it !in alreadyHandled }
        if (candidates.isEmpty()) return Backtrack.None
        candidates.firstOrNull { (mastery[it]?.attempts ?: 0) == 0 }?.let { return Backtrack.Probe(it) }
        val weakest = candidates.minByOrNull { mastery[it]?.rating ?: MasteryModel.START } ?: return Backtrack.None
        val s = mastery[weakest] ?: return Backtrack.None
        return if (MasteryModel.isMastered(s)) Backtrack.None else Backtrack.Review(weakest)
    }
}
