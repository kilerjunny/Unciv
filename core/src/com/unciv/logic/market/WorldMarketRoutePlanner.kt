package com.unciv.logic.market

import com.unciv.logic.city.City
import com.unciv.logic.automation.civilization.GrandStrategyPerf
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
        val tiles: List<HexCoord>,
        val estimatedTransportCost: Int = -1, // GS_ECONOMIC_TRADE_ROUTES_V1
        val originKind: String = "UNKNOWN" // GS_PRODUCTION_CITY_CAPITAL_V1
    )

    // A trade route is recalculated on a different turn. This avoids indefinitely
    // stale routes when terrain, ports, or the availability of cities changes.
    // The cache is bounded, and identical seller/buyer trades reuse work this turn.
    private val cache = LinkedHashMap<String, Route>()
    private const val MaxCacheSize = 128
    private const val MaxVisitedTiles = 10000

    // GS_PRODUCTION_CITY_CAPITAL_V1: resource-specific route, observational only.
    fun plan(seller: Civilization, buyer: Civilization, resourceName: String = ""): Route {
        val game = seller.gameInfo
        val key = "${game.gameId}|${game.turns}|${seller.civID}|${buyer.civID}|$resourceName"
        synchronized(cache) {
            cache[key]?.let { return it }
            val result = calculate(seller, buyer, resourceName)
            if (cache.size >= MaxCacheSize) cache.remove(cache.keys.first())
            cache[key] = result
            return result
        }
    }

    private fun calculate(seller: Civilization, buyer: Civilization, resourceName: String): Route {
        // GS_PRODUCTION_CITY_CAPITAL_V1: Choose CURRENT net-producing cities as origins.
        // The national resource pool does not track which specific unit of stockpiled
        // cargo was produced where. In that case, use the seller's capital as a
        // clearly labelled fallback; never fabricate an origin from a random city.
        // No resource balances or trade settlement code is changed.
        val producingCities = if (resourceName.isBlank()) emptyList<City>() else
            seller.cities.filter { it.getResourcesGeneratedByCity().sumBy(resourceName) > 0 }
        val originKind = if (producingCities.isNotEmpty()) "PRODUCTION" else "CAPITAL_FALLBACK"
        val sourceCities = if (producingCities.isNotEmpty()) producingCities
            else seller.getCapital()?.let { listOf(it) }.orEmpty()

        val startCities = LinkedHashMap<Tile, City>()
        val endCities = LinkedHashMap<Tile, City>()
        for (city in sourceCities) {
            val tile = city.getCenterTile()
            if (tile.isLand && !tile.isImpassible()) startCities[tile] = city
        }
        // Only the BUYER'S CAPITAL is the destination, including landlocked capitals.
        // The existing city-harbor rule handles coastal loading and inland legs.
        val buyerCapital = buyer.getCapital()
        if (buyerCapital != null) {
            val tile = buyerCapital.getCenterTile()
            if (tile.isLand && !tile.isImpassible()) endCities[tile] = buyerCapital
        }

        if (startCities.isEmpty() || endCities.isEmpty()) return unresolved(seller, buyer)

        // GS_ECONOMIC_TRADE_ROUTES_V1: minimum transport index, not minimum tile count.
        // Trade settlement has already happened. This records the observed route only.
        val cheapest = GrandStrategyPerf.measure("market_economic_route") {
            WorldMarketEconomicRoutePlanner.find(seller.gameInfo, startCities.keys, endCities.keys)
        }
        if (cheapest != null) {
            val path = cheapest.tiles
            val water = path.any { it.isWater }
            val inlandLeg = path.drop(1).dropLast(1).any { it.isLand }
            val mode = when {
                !water -> "LAND"
                inlandLeg -> "MIXED"
                else -> "SEA"
            }
            return Route(mode,
                startCities[path.first()]?.name.orEmpty(),
                endCities[path.last()]?.name.orEmpty(),
                path.map { it.position }, cheapest.estimatedCost, originKind)
        }
        // Fail-safe: if the new weighted search hits its work limit or finds no
        // route, the old BFS still works (with an unknown transport index).
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
                    path.map { it.position }, -1, originKind
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
