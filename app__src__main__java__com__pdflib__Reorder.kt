@file:OptIn(ExperimentalFoundationApi::class)

package com.pdflib

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Hold-and-drag reordering for rows of a LazyColumn.
 *
 *  - Long-press a row and move: it lifts, follows the finger, neighbours glide out of the way, the list auto-scrolls at the edges.
 *  - Long-press and release without moving: reported through [onLongPress] (used for multi-select).
 *  - A row can only move inside its own [Group] (the pinned block or the unpinned block of one section),
 *    so pinned rows always stay on top and can never be dragged out of the pinned block.
 */
@Stable
class DragReorder(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxSpeedPx: Float,
    private val slopPx: Float,
    private val onPick: () -> Unit,
    private val onTick: () -> Unit
) {
    /** [keys] = every row key of the section in order; only positions [lo]..[hi] may be reordered; [commit] gets that slice in its new order. */
    class Group(val section: String, val keys: List<Any>, val lo: Int, val hi: Int, val commit: (List<Any>) -> Unit)

    var resolve: (Any) -> Group? = { null }
    var onLongPress: (Any) -> Unit = {}

    var draggedKey by mutableStateOf<Any?>(null); private set
    private var working by mutableStateOf<List<Any>?>(null)
    private var workingSection: String? = null
    private var group: Group? = null
    private var armedKey: Any? = null
    private var armedGroup: Group? = null
    private var downY = 0f
    private var pointerY = 0f
    private var active = false
    private var fingerTop by mutableFloatStateOf(0f)
    private var grab = 0f
    private var settling by mutableStateOf(false)
    private var releaseOffset by mutableFloatStateOf(0f)
    private var awaitingIndex = -1
    private var awaitingSince = 0L
    private var epoch = 0
    private var autoJob: Job? = null

    /** The rows of a section in their live order while a drag is in progress, otherwise exactly [items]. */
    fun <T> arrange(section: String, items: List<T>, key: (T) -> Any): List<T> {
        val w = working
        if (w == null || workingSection != section) return items
        val byKey = HashMap<Any, T>(items.size * 2)
        items.forEach { byKey[key(it)] = it }
        val out = ArrayList<T>(items.size)
        val seen = HashSet<Any>()
        for (k in w) { val v = byKey[k]; if (v != null && seen.add(k)) out.add(v) }
        if (out.size != items.size) items.forEach { if (key(it) !in seen) out.add(it) }
        return out
    }

    /** Called when the stored order changes; lets go of the temporary order once the database agrees with it. */
    fun onSource(section: String, keys: List<Any>) {
        if (!active && draggedKey == null && workingSection == section && working == keys) working = null
    }

    private fun vso() = list.layoutInfo.viewportStartOffset

    /** Long press detected at [y] (list coordinates). Returns false when nothing is under the finger. */
    fun start(y: Float, canDrag: Boolean): Boolean {
        if (draggedKey != null) return false
        val yi = y + vso()
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { yi >= it.offset && yi < it.offset + it.size }
        armedKey = item?.key
        armedGroup = if (canDrag && item != null) resolve(item.key) else null
        downY = y; pointerY = y; active = false
        if (item != null) onPick()
        return item != null
    }

    fun drag(y: Float) {
        pointerY = y
        if (!active) {
            val g = armedGroup ?: return
            if (abs(y - downY) < slopPx) return
            activate(g)
        } else {
            fingerTop = y + vso() - grab
            reposition()
        }
    }

    private fun activate(g: Group) {
        val key = armedKey ?: return
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        group = g; working = g.keys; workingSection = g.section; draggedKey = key
        grab = downY + vso() - info.offset
        fingerTop = pointerY + vso() - grab
        awaitingIndex = -1
        active = true
        epoch++
        startAuto()
    }

    /** Finger lifted. Returns the pressed key when the long press never turned into a drag. */
    fun end(): Any? {
        val k = armedKey
        armedKey = null; armedGroup = null
        if (!active) return k
        finish(true)
        return null
    }

    fun cancel() {
        armedKey = null; armedGroup = null
        if (active) finish(false)
    }

    private fun finish(commit: Boolean) {
        active = false
        autoJob?.cancel()
        val key = draggedKey ?: return
        val g = group ?: return
        val w = working ?: return
        val t = offsetFor(key)
        releaseOffset = t
        settling = true
        val slice = w.subList(g.lo, g.hi + 1).toList()
        val changed = commit && slice != g.keys.subList(g.lo, g.hi + 1)
        if (!commit) working = g.keys
        if (changed) g.commit(slice)
        val mine = epoch
        scope.launch {
            animate(t, 0f, animationSpec = tween(180, easing = FastOutSlowInEasing)) { v, _ -> releaseOffset = v }
            settling = false
            draggedKey = null
            if (!changed) { if (epoch == mine) working = null }
            else { delay(900); if (epoch == mine && draggedKey == null) working = null }
        }
    }

    /** Vertical shift of the lifted row relative to the slot it currently occupies. */
    fun offsetFor(key: Any): Float {
        if (key != draggedKey) return 0f
        if (settling) return releaseOffset
        val vis = list.layoutInfo.visibleItemsInfo
        val me = vis.firstOrNull { it.key == key } ?: return 0f
        return clampedTop(me, vis) - me.offset
    }

    private fun clampedTop(me: LazyListItemInfo, vis: List<LazyListItemInfo>): Float {
        val g = group ?: return fingerTop
        val w = working ?: return fingerTop
        var lo = Float.NEGATIVE_INFINITY
        var hi = Float.POSITIVE_INFINITY
        if (g.lo in w.indices) { val k = w[g.lo]; vis.firstOrNull { it.key == k }?.let { lo = it.offset.toFloat() } }
        if (g.hi in w.indices) { val k = w[g.hi]; vis.firstOrNull { it.key == k }?.let { hi = (it.offset + it.size - me.size).toFloat() } }
        return fingerTop.coerceIn(lo, maxOf(lo, hi))
    }

    private fun reposition() {
        val g = group ?: return
        val key = draggedKey ?: return
        val w = working ?: return
        val vis = list.layoutInfo.visibleItemsInfo
        val me = vis.firstOrNull { it.key == key } ?: return
        if (awaitingIndex >= 0) {
            val stale = (System.nanoTime() - awaitingSince) > 250_000_000L
            if (me.index != awaitingIndex && !stale) return
            awaitingIndex = -1
        }
        val centre = clampedTop(me, vis) + me.size / 2f
        val from = w.indexOf(key)
        if (from < 0) return
        var target = -1
        for (it in vis) {
            if (it.key == key) continue
            if (centre >= it.offset && centre <= it.offset + it.size) {
                val idx = w.indexOf(it.key)
                if (idx in g.lo..g.hi) { target = idx; break }
            }
        }
        if (target >= 0 && target != from) {
            val m = w.toMutableList()
            m.removeAt(from); m.add(target, key)
            working = m
            awaitingIndex = me.index + (target - from)
            awaitingSince = System.nanoTime()
            onTick()
        }
    }

    private fun startAuto() {
        autoJob?.cancel()
        autoJob = scope.launch {
            var last = withFrameNanosCompat()
            while (isActive) {
                val now = withFrameNanosCompat()
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val h = list.layoutInfo.viewportSize.height.toFloat()
                val y = pointerY
                val dir = when {
                    y < edgePx -> -((edgePx - y) / edgePx).coerceIn(0f, 1f)
                    y > h - edgePx -> ((y - (h - edgePx)) / edgePx).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (dir != 0f) {
                    list.scrollBy(dir * abs(dir) * maxSpeedPx * dt)
                    fingerTop = pointerY + vso() - grab
                    reposition()
                }
            }
        }
    }
}

private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }

/** Modifier for a row inside `items { }`: glides to its new slot normally, lifts and follows the finger while dragged. */
fun LazyItemScope.reorderItem(r: DragReorder, key: Any, lifted: Color): Modifier =
    if (r.draggedKey == key) {
        Modifier.zIndex(1f).graphicsLayer {
            translationY = r.offsetFor(key)
            scaleX = 1.02f; scaleY = 1.02f
            shadowElevation = 14.dp.toPx()
            shape = RoundedCornerShape(14.dp)
            clip = true
        }.background(lifted)
    } else Modifier.animateItemPlacement()

/** Put on the LazyColumn. Works on the Initial pass so the list never scrolls and the row never "clicks" while a drag is in progress. */
fun Modifier.reorderGestures(r: DragReorder, canDrag: Boolean): Modifier = pointerInput(canDrag) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val lp = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!r.start(lp.position.y, canDrag)) return@awaitEachGesture
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val c = e.changes.firstOrNull { it.id == down.id }
            e.changes.forEach { it.consume() }
            if (c == null || !c.pressed) break
            r.drag(c.position.y)
        }
        r.end()?.let { r.onLongPress(it) }
    }
}
