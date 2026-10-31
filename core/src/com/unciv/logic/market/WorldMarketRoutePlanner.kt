package com.unciv.logic.market

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import java.util.ArrayDeque

/**
 * GS_TRADE_ROUTE_MULTIMODAL_V2
 *
 * OBSERVATION ONLY: Finds a connected route for already-completed market trades.
 * No units, inventory changes, gold transfers, turn delays, or AI purchases.
 *
 * - A route can cross land and water (LAND, SEA, MIXED).
 * - Land/water transitions are allowed only through coastal CITY CENTER tiles.
 * - All cities of both civilizations are considered in ONE multi-source BFS, so the
 *   nearest cities by straight-line distance need not be selected.
 * - Physical passability only; foreign borders, canals, blockades, and tariffs
 *   are explicitly NOT modeled at this stage.
 */
object WorldMarketRoutePlanner {
    data class Route(
        val mode: String,
        val originCityName: String,
        val destinationCityName: String,
        val tiles: List<HexCoord>
    )

    // A trade route is recalculated on a different turn. This avoids indefinitely
    // stale routes when terrain, ports, or the availability of cities changes.
    // The cache is bounded, and identical seller/buyer trades reuse work this turn.
    private val cache = LinkedHashMap<String, Route>()
    private const val MaxCacheSize = 128
    private const val MaxVisitedTiles = 10000

    fun plan(seller: Civilization, buyer: Civilization): Route {
        val game = seller.gameInfo
        val key = "${game.gameId}|${game.turns}|${seller.civID}|${buyer.civID}"
        synchronized(cache) {
            cache[key]?.let { return it }
            val result = calculate(seller, buyer)
            if (cache.size >= MaxCacheSize) cache.remove(cache.keys.first())
            cache[key] = result
            return result
        }
    }

    private fun calculate(seller: Civilization, buyer: Civilization): Route {
        // Using the actual tile as key prevents misleading straight-line pairing.
        val startCities = LinkedHashMap<Tile, City>()
        val endCities = LinkedHashMap<Tile, City>()
        for (city in seller.cities) {
            val tile = city.getCenterTile()
            if (tile.isLand && !tile.isImpassible()) startCities[tile] = city
        }
        for (city in buyer.cities) {
            val tile = city.getCenterTile()
            if (tile.isLand && !tile.isImpassible()) endCities[tile] = city
        }

        if (startCities.isEmpty() || endCities.isEmpty()) return unresolved(seller, buyer)
        val targetTiles = endCities.keys
        val queue = ArrayDeque<Tile>()
        val predecessor = HashMap<Tile, Tile?>()

        // Multi-source BFS finds the shortest number of map edges among every
        // candidate seller-city/buyer-city pair, not merely one aerially closest pair.
        for (start in startCities.keys) {
            predecessor[start] = null
            queue.addLast(start)
        }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current in targetTiles) {
                val path = ArrayList<Tile>()
                var step: Tile? = current
                while (step != null) {
                    path.add(step)
                    step = predecessor[step]
                }
                path.reverse()
                val water = path.any { it.isWater }
                val inlandLeg = path.drop(1).dropLast(1).any { it.isLand }
                val mode = when {
                    !water -> "LAND"
                    inlandLeg -> "MIXED"
                    else -> "SEA"
                }
                return Route(
                    mode,
                    startCities[path.first()]?.name.orEmpty(),
                    endCities[path.last()]?.name.orEmpty(),
                    path.map { it.position }
                )
            }
            if (predecessor.size >= MaxVisitedTiles) break
            for (next in current.neighbors) {
                if (next in predecessor || !passable(next)) continue
                // A shoreline isn't itself a harbor: only coastal cities can
                // load, unload or transfer between land and water.
                if (current.isLand != next.isLand) {
                    val harbor = if (current.isLand) current else next
                    if (!harbor.isCityCenter()) continue
                }
                predecessor[next] = current
                queue.addLast(next)
            }
        }
        return unresolved(seller, buyer)
    }

    private fun passable(tile: Tile): Boolean =
        !tile.isImpassible() && (tile.isLand || tile.isWater)

    private fun unresolved(seller: Civilization, buyer: Civilization) = Route(
        "UNRESOLVED",
        seller.getCapital()?.name.orEmpty(),
        buyer.getCapital()?.name.orEmpty(),
        emptyList()
    )
}
