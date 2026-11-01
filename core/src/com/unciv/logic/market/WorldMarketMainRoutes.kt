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
    private const val MinCorridorEdges = 8
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

    // GS_CONNECTED_TRADE_BACKBONE_V2
    // A main corridor follows a CONTINUOUS piece of an actual observed trade
    // route, including the lightly-used bridge between shared sections.
    // This avoids drawing small unrelated traffic fragments as main lines.
    internal fun selectRows(rows: List<MarketRouteStat>, maximum: Int): List<MainTradeCorridor> {
        if (maximum <= 0) return emptyList()
        // The input is deliberately capped. The only work done during turns is
        // on the first economic path request, which already caches the result.
        val inputs = rows.asSequence()
            .filter { it.tradeCount > 0 && it.routeTiles.size in (MinCorridorEdges + 1)..(MaxCorridorLength + 1) }
            .sortedWith(compareByDescending<MarketRouteStat> { it.tradeCount }
                .thenBy { it.sellerCivId }.thenBy { it.buyerCivId }
                .thenBy { it.routeHash })
            .take(MaxInputRows).toList()
        if (inputs.isEmpty()) return emptyList()

        val traffic = HashMap<Edge, Traffic>()
        for (row in inputs) for (i in 0 until row.routeTiles.size - 1) {
            val a = row.routeTiles[i]
            val b = row.routeTiles[i + 1]
            if (a == b) continue
            val flow = traffic.getOrPut(edge(a, b)) { Traffic() }
            flow.trades += row.tradeCount
            flow.quantity += row.quantity.coerceAtLeast(0)
        }
        if (traffic.isEmpty()) return emptyList()
        val peak = traffic.values.maxOf { it.trades }
        val threshold = maxOf(2, (peak * 0.35).roundToInt())

        data class Candidate(val tiles: List<HexCoord>, val edges: List<Edge>, val mode: String)
        val candidates = ArrayList<Candidate>()
        for (row in inputs) {
            val edges = (0 until row.routeTiles.size - 1).map { i ->
                edge(row.routeTiles[i], row.routeTiles[i + 1])
            }
            // Find repeat-used parts of this authentic route; include the
            // connecting lower-traffic segments so the resulting trunk is whole.
            val strong = edges.indices.filter { (traffic[edges[it]]?.trades ?: 0) >= threshold }
            if (strong.size < 4) continue
            val first = strong.first()
            val last = strong.last()
            if (last - first + 1 < MinCorridorEdges) continue
            val path = row.routeTiles.subList(first, last + 2).toList()
            val pathEdges = edges.subList(first, last + 1).toList()
            candidates.add(Candidate(path, pathEdges, row.routeMode))
        }
        if (candidates.isEmpty()) return emptyList()

        val occupied = HashSet<Edge>()
        val output = ArrayList<MainTradeCorridor>()
        val used = BooleanArray(candidates.size)
        while (output.size < maximum) {
            var selected = -1
            var highestScore = 0L
            for (i in candidates.indices) {
                if (used[i]) continue
                val candidate = candidates[i]
                var fresh = 0
                var score = 0L
                for (segment in candidate.edges) if (segment !in occupied) {
                    fresh++
                    val weight = traffic.getValue(segment)
                    score += weight.trades * 5L + weight.quantity.coerceAtMost(10)
                }
                // A second trunk must be substantially independent; otherwise
                // several overlapping versions of one international route use
                // up all world slots.
                if (fresh < MinCorridorEdges || fresh * 4 < candidate.edges.size) continue
                // When actual traffic is comparable, favour sea-capable routes.
                if (candidate.mode == "SEA" || candidate.mode == "MIXED")
                    score = score * 11 / 10
                if (score > highestScore) {
                    highestScore = score
                    selected = i
                }
            }
            if (selected < 0) break
            used[selected] = true
            val chosen = candidates[selected]
            occupied.addAll(chosen.edges)
            output.add(MainTradeCorridor(
                chosen.tiles,
                chosen.edges.maxOf { traffic.getValue(it).trades },
                chosen.edges.sumOf { traffic.getValue(it).trades }
            ))
        }
        return output
    }

    private fun other(edge: Edge, one: HexCoord): HexCoord =
        if (edge.a == one) edge.b else edge.a

    private fun edge(a: HexCoord, b: HexCoord): Edge =
        if (a.x < b.x || (a.x == b.x && a.y <= b.y)) Edge(a, b) else Edge(b, a)
}
