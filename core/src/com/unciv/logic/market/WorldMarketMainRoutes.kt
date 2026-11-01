package com.unciv.logic.market

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * GS_WORLD_MAIN_ROUTES_V1
 * Observation-only global trade corridors. No trading, pathfinding, ownership,
 * resource transfer or save changes. Calculated on opening the trade-map panel.
 *
 * A corridor is a continuous sequence of shared trade-path edges, not a
 * seller/buyer contract and not a route owned by one civilization.
 */
object WorldMarketMainRoutes {
    data class MainTradeCorridor(
        val tiles: List<HexCoord>,
        val peakTrades: Int,
        val totalSegmentTrades: Int
    )

    private data class Edge(val a: HexCoord, val b: HexCoord)
    private class Traffic(var trades: Int = 0, var quantity: Int = 0)
    private const val MaxCorridorLength = 256
    private const val MinCorridorEdges = 4
    private const val MaxInputRows = 600 // Bound map-opening CPU/memory use on Android.

    /** Radius 40 hex map = 4,681 tiles -> 8; radius 50 -> 10.
     * Works for rectangular custom maps as well, by using actual tile count. */
    fun maximumCount(game: GameInfo): Int {
        val tiles = game.tileMap.tileList.size.coerceAtLeast(1)
        return (8.0 * sqrt(tiles.toDouble() / 4681.0)).roundToInt().coerceIn(4, 12)
    }

    /** World-wide, not restricted by player country or route-type UI filters.
     * If traffic is too sparse to reveal a shared backbone, return no main routes. */
    fun select(game: GameInfo, rows: List<MarketRouteStat>): List<MainTradeCorridor> =
        selectRows(rows, maximumCount(game))

    internal fun selectRows(rows: List<MarketRouteStat>, maximum: Int): List<MainTradeCorridor> {
        if (maximum <= 0) return emptyList()
        val traffic = HashMap<Edge, Traffic>()
        for (row in rows.sortedByDescending { it.tradeCount }.take(MaxInputRows)) {
            if (row.tradeCount <= 0 || row.routeTiles.size < 2) continue
            for (i in 0 until row.routeTiles.size - 1) {
                val a = row.routeTiles[i]
                val b = row.routeTiles[i + 1]
                if (a == b) continue
                val key = edge(a, b)
                val flow = traffic.getOrPut(key) { Traffic() }
                flow.trades += row.tradeCount
                flow.quantity += row.quantity.coerceAtLeast(0)
            }
        }
        if (traffic.isEmpty()) return emptyList()

        val peak = traffic.values.maxOf { it.trades }
        // Shared traffic is essential. Higher-flow networks require stronger
        // segments to be included; avoids labeling every minor road as a trunk.
        val minimum = maxOf(2, (peak * 0.35).roundToInt())
        val strongEdges = traffic.keys.filter { traffic.getValue(it).trades >= minimum }
        if (strongEdges.isEmpty()) return emptyList()

        val adjacency = HashMap<HexCoord, MutableList<Edge>>()
        for (edge in strongEdges) {
            adjacency.getOrPut(edge.a) { ArrayList() }.add(edge)
            adjacency.getOrPut(edge.b) { ArrayList() }.add(edge)
        }
        val byPriority = compareByDescending<Edge> { traffic.getValue(it).trades }
            .thenByDescending { traffic.getValue(it).quantity }
            .thenBy { it.a.x }.thenBy { it.a.y }.thenBy { it.b.x }.thenBy { it.b.y }
        val sorted = strongEdges.sortedWith(byPriority)
        val assigned = HashSet<Edge>()
        val result = ArrayList<MainTradeCorridor>()

        for (seed in sorted) {
            if (result.size >= maximum) break
            if (seed in assigned) continue
            val points = ArrayList<HexCoord>()
            points.add(seed.a)
            points.add(seed.b)
            val localEdges = HashSet<Edge>()
            localEdges.add(seed)
            val visited = HashSet<HexCoord>()
            visited.add(seed.a)
            visited.add(seed.b)

            fun extend(atFront: Boolean) {
                while (points.size - 1 < MaxCorridorLength) {
                    val end = if (atFront) points.first() else points.last()
                    val nextEdge = adjacency[end].orEmpty().asSequence()
                        .filter { it !in assigned && it !in localEdges }
                        .filter { other(it, end) !in visited }
                        .sortedWith(byPriority)
                        .firstOrNull() ?: break
                    val next = other(nextEdge, end)
                    if (atFront) points.add(0, next) else points.add(next)
                    localEdges.add(nextEdge)
                    visited.add(next)
                }
            }
            extend(false)
            extend(true)
            if (localEdges.size < MinCorridorEdges) continue
            val peakTrades = localEdges.maxOf { traffic.getValue(it).trades }
            val totalSegmentTrades = localEdges.sumOf { traffic.getValue(it).trades }
            assigned.addAll(localEdges)
            result.add(MainTradeCorridor(points, peakTrades, totalSegmentTrades))
        }
        return result
    }

    private fun other(edge: Edge, one: HexCoord): HexCoord =
        if (edge.a == one) edge.b else edge.a

    private fun edge(a: HexCoord, b: HexCoord): Edge =
        if (a.x < b.x || (a.x == b.x && a.y <= b.y)) Edge(a, b) else Edge(b, a)
}
