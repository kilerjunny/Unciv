package com.unciv.logic.market

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
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
    // GS_TERRITORY_TOLLS_SUPPLY_V1: per-trade index cost breakdown.
    data class Result(
        val tiles: List<Tile>, val estimatedCost: Int,
        val terrainCost: Int, val mainSaving: Int,
        val supplyCost: Int, val tollCost: Int, val portCost: Int
    )

    private data class Edge(val a: HexCoord, val b: HexCoord)
    // GS_CONNECTED_TRADE_BACKBONE_V2: prefer a main line on equal-cost routes.
    private data class SearchNode(val tile: Tile, val cost: Int, val mainSteps: Int)
    private data class Score(val cost: Int, val mainSteps: Int)

    // These are provisional balance values, NOT gold/turn or charged transport fees.
    // Terrain, main-route discount, supply, border toll and harbor costs
    // are now calculated together by WorldMarketTerritoryCosts.
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
    fun find(seller: Civilization, buyer: Civilization,
             origins: Collection<Tile>, destinations: Set<Tile>): Result? {
        val game = seller.gameInfo
        if (origins.isEmpty() || destinations.isEmpty()) return null
        val main = mainEdges(game)
        val best = HashMap<Tile, Score>()
        val parent = HashMap<Tile, Tile?>()
        val queue = PriorityQueue<SearchNode>(compareBy<SearchNode> { it.cost }
            .thenByDescending { it.mainSteps }
            .thenBy { it.tile.position.x }.thenBy { it.tile.position.y })

        for (start in origins) {
            if (start in best) continue
            best[start] = Score(0, 0)
            parent[start] = null
            queue.add(SearchNode(start, 0, 0))
        }

        var settled = 0
        while (queue.isNotEmpty() && settled < MaxSettledTiles) {
            val current = queue.poll()
            if (best[current.tile] != Score(current.cost, current.mainSteps)) continue // stale queue entry
            settled++
            if (current.tile in destinations) {
                val reversed = ArrayList<Tile>()
                var at: Tile? = current.tile
                while (at != null) {
                    reversed.add(at)
                    at = parent[at]
                }
                reversed.reverse()
                val detail = WorldMarketTerritoryCosts.breakdown(reversed, seller, buyer) { a, b ->
                    edge(a.position, b.position) in main
                }
                return Result(reversed, current.cost, detail.terrain, detail.mainSaving,
                    detail.supply, detail.toll, detail.port)
            }
            for (next in current.tile.neighbors) {
                if (!passable(next)) continue
                if (current.tile.isLand != next.isLand) {
                    // Keep the original physical constraint: only a coastal city
                    // centre can load/unload between land and water.
                    val harbor = if (current.tile.isLand) current.tile else next
                    if (!harbor.isCityCenter()) continue
                }
                val isMain = edge(current.tile.position, next.position) in main
                val nextCost = current.cost + WorldMarketTerritoryCosts.stepCost(
                    current.tile, next, seller, buyer, isMain)
                val nextMain = current.mainSteps +
                    (if (edge(current.tile.position, next.position) in main) 1 else 0)
                val previous = best[next]
                // Cost remains the PRIMARY objective. A connected main corridor
                // only wins a tie, never a more-expensive alternative.
                if (previous == null || nextCost < previous.cost ||
                    (nextCost == previous.cost && nextMain > previous.mainSteps)) {
                    best[next] = Score(nextCost, nextMain)
                    parent[next] = current.tile
                    queue.add(SearchNode(next, nextCost, nextMain))
                }
            }
        }
        return null
    }

    private fun passable(tile: Tile) =
        !tile.isImpassible() && (tile.isLand || tile.isWater)

    private fun edge(a: HexCoord, b: HexCoord): Edge =
        if (a.x < b.x || (a.x == b.x && a.y <= b.y)) Edge(a, b) else Edge(b, a)
}
