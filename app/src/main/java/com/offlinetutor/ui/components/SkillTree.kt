package com.offlinetutor.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import com.offlinetutor.domain.NodeState
import com.offlinetutor.ui.design.bounceClick
import com.offlinetutor.ui.theme.Palette

data class TreeNode(val id: String, val title: String, val state: NodeState, val row: Int, val stepId: Long)
data class TreeEdge(val from: String, val to: String, val dashed: Boolean)

private val nodeSize = 58.dp
private val rowHeight = 124.dp
private val colWidth = 104.dp

/**
 * 1f: roadmap as a skill tree. Rows = prerequisite depth; edges drawn on a Canvas behind positioned nodes.
 * States: locked / active (pulsing) / mastered / fix / review.
 */
@Composable
fun SkillTree(nodes: List<TreeNode>, edges: List<TreeEdge>, color: Color, onNode: (TreeNode) -> Unit, modifier: Modifier = Modifier) {
    if (nodes.isEmpty()) return
    val rows = nodes.groupBy { it.row }.toSortedMap()
    val maxCols = rows.values.maxOf { it.size }
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = max(maxWidth, colWidth * maxCols)
        val height = rowHeight * rows.size
        // Centre of each node, spreading each row evenly across the width.
        val centers = HashMap<String, Pair<Dp, Dp>>()
        rows.entries.forEachIndexed { r, (_, list) ->
            list.forEachIndexed { i, n -> centers[n.id] = (width * ((i + 0.5f) / list.size)) to (rowHeight * r + nodeSize / 2 + 8.dp) }
        }
        val muted = MaterialTheme.colorScheme.outline
        Box(Modifier.horizontalScroll(rememberScrollState()).width(width).height(height)) {
            Canvas(Modifier.fillMaxSize()) {
                for (e in edges) {
                    val a = centers[e.from] ?: continue
                    val b = centers[e.to] ?: continue
                    val start = with(density) { Offset(a.first.toPx(), a.second.toPx() + nodeSize.toPx() / 2) }
                    val end = with(density) { Offset(b.first.toPx(), b.second.toPx() - nodeSize.toPx() / 2) }
                    val mid = (start.y + end.y) / 2
                    val path = Path().apply { moveTo(start.x, start.y); cubicTo(start.x, mid, end.x, mid, end.x, end.y) }
                    val done = nodes.firstOrNull { it.id == e.from }?.state == NodeState.MASTERED
                    drawPath(path, if (done) color else muted, style = Stroke(
                        width = 5f,
                        pathEffect = if (e.dashed || !done) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null,
                    ))
                }
            }
            for (n in nodes) {
                val (cx, cy) = centers.getValue(n.id)
                Column(
                    Modifier.offset(x = cx - colWidth / 2, y = cy - nodeSize / 2).width(colWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NodeBubble(n, color) { onNode(n) }
                    Text(n.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun NodeBubble(n: TreeNode, color: Color, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "node")
    val s by pulse.animateFloat(1f, if (n.state == NodeState.ACTIVE) 1.12f else 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "s")
    val review = Color(0xFF8E5BA8)
    val (bg, fg, icon) = when (n.state) {
        NodeState.MASTERED -> Triple(color, Color.White, Icons.Filled.Check)
        NodeState.ACTIVE -> Triple(MaterialTheme.colorScheme.surface, color, Icons.Filled.PlayArrow)
        NodeState.LOCKED -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Lock)
        NodeState.FIX -> Triple(Palette.marigold, Color(0xFF3A2300), Icons.Filled.Healing)
        NodeState.REVIEW -> Triple(review, Color.White, Icons.Filled.Replay)
    }
    val ring = when (n.state) { NodeState.ACTIVE -> color; NodeState.LOCKED -> MaterialTheme.colorScheme.outline; else -> bg }
    Box(
        Modifier.size(nodeSize).scale(s).bounceClick(onClick = onClick).background(bg, CircleShape).border(3.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp)) }
}
