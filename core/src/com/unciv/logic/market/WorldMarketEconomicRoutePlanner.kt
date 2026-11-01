package com.unciv.logic.market

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import java.lang.ref.WeakReference
import java.util.PriorityQueue

/**
 * GS_ECONOMIC_TRADE_ROUTES_V1
 * Observation-only minimum-shipping-cost path for completed market transactions.
 * NO gold deduction, cargo delivery, unit movement, foreign-border or blockade rules.
 * The cost is an abstract comparison index, never a monetary price.
 *
 * All coastal/deep-ocean and main-trunk tile costs are positive; Dijkstra is therefore
 * valid. Shared main corridors are frozen per game turn so trade matching order cannot
 * cause a different backbone in the middle of a turn.
 */
object WorldMarketEconomicRoutePlanner {
    data class Result(val tiles: List<Tile>, val estimatedCost: Int)

    private data class Edge(val a: HexCoord, val b: HexCoord)
    private data class SearchNode(val tile: Tile, val cost: Int)

    // These are provisional balance values, NOT gold/turn or charged transport fees.
    private const val InlandStep = 12
    private const val CoastalStep = 7
    private const val OpenOceanStep = 4
    private const val PortTransfer = 16
    private const val MaxSettledTiles = 10000

    // Only the most recent game/turn is retained. WeakReference avoids retaining a
    // full game graph if the player starts or loads another game.
    private var cachedGame: WeakReference<GameInfo>? = null
    private var cachedTurn = -1
    private var cachedBackbone: Set<Edge> = emptySet()

    @Synchronized
    private fun mainEdges(game: GameInfo): Set<Edge> {
        if (cachedGame?.get() === game && cachedTurn == game.turns) return cachedBackbone
        val result = HashSet<Edge>()
        val corridors = WorldMarketMainRoutes.select(game, WorldMarketRouteStats.windowRows(game))
        for (corridor in corridors) {
            for (i in 0 until corridor.tiles.size - 1)
                result.add(edge(corridor.tiles[i], corridor.tiles[i + 1]))
        }
        cachedGame = WeakReference(game)
        cachedTurn = game.turns
        cachedBackbone = result
        return result
    }

    /** Multi-source (any seller city) -> multi-target (any buyer city). */
    fun find(game: GameInfo, origins: Collection<Tile>, destinations: Set<Tile>): Result? {
        if (origins.isEmpty() || destinations.isEmpty()) return null
        val main = mainEdges(game)
        val best = HashMap<Tile, Int>()
        val parent = HashMap<Tile, Tile?>()
        val queue = PriorityQueue<SearchNode>(compareBy<SearchNode> { it.cost }
            .thenBy { it.tile.position.x }.thenBy { it.tile.position.y })

        for (start in origins) {
            if (start in best) continue
            best[start] = 0
            parent[start] = null
            queue.add(SearchNode(start, 0))
        }

        var settled = 0
        while (queue.isNotEmpty() && settled < MaxSettledTiles) {
            val current = queue.poll()
            if (best[current.tile] != current.cost) continue // stale queue entry
            settled++
            if (current.tile in destinations) {
                val reversed = ArrayList<Tile>()
                var at: Tile? = current.tile
                while (at != null) {
                    reversed.add(at)
                    at = parent[at]
                }
                reversed.reverse()
                return Result(reversed, current.cost)
            }
            for (next in current.tile.neighbors) {
                if (!passable(next)) continue
                if (current.tile.isLand != next.isLand) {
                    // Keep the original physical constraint: only a coastal city
                    // centre can load/unload between land and water.
                    val harbor = if (current.tile.isLand) current.tile else next
                    if (!harbor.isCityCenter()) continue
                }
                val nextCost = current.cost + stepCost(current.tile, next, main)
                val previousCost = best[next]
                if (previousCost == null || nextCost < previousCost) {
                    best[next] = nextCost
                    parent[next] = current.tile
                    queue.add(SearchNode(next, nextCost))
                }
            }
        }
        return null
    }

    private fun passable(tile: Tile) =
        !tile.isImpassible() && (tile.isLand || tile.isWater)

    private fun stepCost(from: Tile, to: Tile, main: Set<Edge>): Int {
        // A segment's cost must be symmetric for trade in either direction.
        val base = (terrainCost(from) + terrainCost(to) + 1) / 2
        // Established shared trunk lanes are cheaper due to consolidation.
        // A 25% discount is intentionally modest: longer detours can be worth
        // taking, but an extremely circuitous backbone should not dominate.
        val laneCost = if (edge(from.position, to.position) in main)
            ((base * 3 + 3) / 4).coerceAtLeast(1) else base
        return laneCost + if (from.isLand != to.isLand) PortTransfer else 0
    }

    private fun terrainCost(tile: Tile) = when {
        tile.isLand -> InlandStep
        tile.isOcean -> OpenOceanStep
        else -> CoastalStep
    }

    private fun edge(a: HexCoord, b: HexCoord): Edge =
        if (a.x < b.x || (a.x == b.x && a.y <= b.y)) Edge(a, b) else Edge(b, a)
}
