package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.Batch
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.scenes.scene2d.Actor
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.HexCoord
import com.unciv.logic.market.MarketRouteStat
import com.unciv.ui.images.ImageGetter
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Lightweight, non-interactive geographic route overview in the market tab.
 * Uses the existing atlas; no framebuffer, new texture, pathfinding, or unit actors.
 * Respects explored tiles, including along route segments.
 */
class WorldMarketRouteMapActor(
    game: GameInfo,
    viewer: Civilization,
    spectator: Boolean,
    routes: List<MarketRouteStat>
) : Actor() {
    private data class Dot(val worldX: Float, val worldY: Float, val water: Boolean)
    private data class Segment(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)
    private data class PaintedRoute(val type: String, val count: Int, val segments: List<Segment>)

    private val dots = ArrayList<Dot>()
    private val lines = ArrayList<PaintedRoute>()
    private var minX = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private val land = Color(0.31f, 0.44f, 0.35f, 1f)
    private val sea = Color(0.10f, 0.24f, 0.37f, 1f)
    private val canvas = Color(0.035f, 0.11f, 0.18f, 1f)
    private val landPath = Color(1f, 0.75f, 0.30f, 1f)
    private val seaPath = Color(0.29f, 0.78f, 1f, 1f)
    private val mixedPath = Color(0.98f, 0.51f, 0.82f, 1f)

    init {
        val world = game.tileMap
        // Derive fixed geographic bounds from the full map, without revealing unexplored land.
        for (tile in world.tileList) {
            val point = HexMath.hex2WorldCoords(tile.position)
            minX = min(minX, point.x)
            maxX = max(maxX, point.x)
            minY = min(minY, point.y)
            maxY = max(maxY, point.y)
            if (spectator || tile.isExplored(viewer))
                dots.add(Dot(point.x, point.y, tile.isWater))
        }
        if (world.tileList.isEmpty()) {
            minX = 0f; maxX = 1f; minY = 0f; maxY = 1f
        }
        for (route in routes) {
            val segments = ArrayList<Segment>()
            val points: List<HexCoord> = route.routeTiles
            for (i in 0 until points.size - 1) {
                val a = points[i]
                val b = points[i + 1]
                val tileA = world.getOrNull(a.x, a.y)
                val tileB = world.getOrNull(b.x, b.y)
                if (tileA == null || tileB == null) continue
                if (!spectator && (!tileA.isExplored(viewer) || !tileB.isExplored(viewer))) continue
                val p = HexMath.hex2WorldCoords(a)
                val q = HexMath.hex2WorldCoords(b)
                // Don't draw an erroneous line across the world-wrap seam.
                if (hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) > 3.0) continue
                segments.add(Segment(p.x, p.y, q.x, q.y))
            }
            if (segments.isNotEmpty()) lines.add(PaintedRoute(route.routeMode, route.tradeCount, segments))
        }
    }

    override fun draw(batch: Batch, parentAlpha: Float) {
        if (width <= 2f || height <= 2f) return
        val pixel = ImageGetter.getWhiteDotDrawable().region
        val old = batch.color.cpy()
        batch.color = canvas
        batch.draw(pixel, x, y, width, height)

        val pad = 12f
        val sx = (width - 2f * pad) / max(1f, maxX - minX)
        val sy = (height - 2f * pad) / max(1f, maxY - minY)
        val scale = max(0.01f, min(sx, sy))
        val offsetX = x + (width - (maxX - minX) * scale) * 0.5f
        val offsetY = y + (height - (maxY - minY) * scale) * 0.5f
        fun px(v: Float) = offsetX + (v - minX) * scale
        fun py(v: Float) = offsetY + (v - minY) * scale

        // Single atlas texture for the background and lines: batch-friendly on Android.
        val dotSize = max(1.2f, min(4.5f, scale * 0.95f))
        for (dot in dots) {
            batch.color = if (dot.water) sea else land
            batch.draw(pixel, px(dot.worldX) - dotSize / 2f,
                py(dot.worldY) - dotSize / 2f, dotSize, dotSize)
        }
        for (route in lines) {
            batch.color = when (route.type) {
                "LAND" -> landPath
                "SEA" -> seaPath
                else -> mixedPath
            }
            val thickness = min(5f, 1.5f + route.count * 0.28f)
            for (segment in route.segments) {
                val x1 = px(segment.fromX)
                val y1 = py(segment.fromY)
                val dx = px(segment.toX) - x1
                val dy = py(segment.toY) - y1
                val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (length <= 0.001f) continue
                val angle = atan2(dy, dx) * MathUtils.radiansToDegrees
                batch.draw(pixel, x1, y1 - thickness / 2f,
                    0f, thickness / 2f, length, thickness, 1f, 1f, angle)
            }
        }
        batch.color = old
    }
}
