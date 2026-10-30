package com.unciv.logic.market

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import java.util.ArrayDeque

/**
 * Phase 1: physical, observational routes only. Does not move resources, charge gold,
 * generate units or alter existing world-market trade acceptance.
 *
 * LAND: traversable land tiles. SEA: continuous water between coastal cities.
 * Diplomatic access, coastal canals, piracy, trade costs and multimodal transfer
 * are intentionally deferred to later phases.
 */
object WorldMarketRoutePlanner {
    data class Route(
        val mode: String,
        val originCityName: String,
        val destinationCityName: String,
        val tiles: List<HexCoord>
    )

    // Bounded LRU: geographic path is stable until the map or endpoints change.
    // Empty lists cache searches with no route, avoiding repeated map-wide scans.
    private val cache = LinkedHashMap<String, List<HexCoord>>()
    private const val MaxCacheSize = 128
    private const val MaxVisitedTiles = 6000

    fun plan(seller: Civilization, buyer: Civilization): Route {
        val landPair = closestPair(seller, buyer, coastalOnly = false)
        val seaPair = closestPair(seller, buyer, coastalOnly = true)

        val land = landPair?.let { pair ->
            cached(seller, pair.first, pair.second, "LAND") {
                val from = pair.first.getCenterTile()
                val to = pair.second.getCenterTile()
                search(listOf(from), setOf(to)) { it.isLand && !it.isImpassible() }
            }
        }.orEmpty()

        val sea = seaPair?.let { pair ->
            cached(seller, pair.first, pair.second, "SEA") {
                val from = pair.first.getCenterTile()
                val to = pair.second.getCenterTile()
                val starts = from.neighbors.filter { it.isWater && !it.isImpassible() }.toList()
                val ends = to.neighbors.filter { it.isWater && !it.isImpassible() }.toSet()
                if (starts.isEmpty() || ends.isEmpty()) emptyList()
                else {
                    val seaTiles = search(starts, ends) { it.isWater && !it.isImpassible() }
                    if (seaTiles.isEmpty()) emptyList()
                    else listOf(from.position) + seaTiles + listOf(to.position)
                }
            }
        }.orEmpty()

        return when {
            land.isNotEmpty() && (sea.isEmpty() || land.size <= sea.size) -> {
                val pair = landPair!!
                Route("LAND", pair.first.name, pair.second.name, land)
            }
            sea.isNotEmpty() -> {
                val pair = seaPair!!
                Route("SEA", pair.first.name, pair.second.name, sea)
            }
            else -> {
                val pair = landPair ?: seaPair
                Route("UNRESOLVED", pair?.first?.name.orEmpty(), pair?.second?.name.orEmpty(), emptyList())
            }
        }
    }

    private fun closestPair(
        seller: Civilization,
        buyer: Civilization,
        coastalOnly: Boolean
    ): Pair<City, City>? {
        var best: Pair<City, City>? = null
        var shortest = Int.MAX_VALUE
        for (from in seller.cities) {
            val start = from.getCenterTile()
            if (coastalOnly && start.neighbors.none { it.isWater && !it.isImpassible() }) continue
            for (to in buyer.cities) {
                val end = to.getCenterTile()
                if (coastalOnly && end.neighbors.none { it.isWater && !it.isImpassible() }) continue
                val distance = start.aerialDistanceTo(end)
                if (distance < shortest) {
                    best = Pair(from, to)
                    shortest = distance
                }
            }
        }
        return best
    }

    private fun cached(
        seller: Civilization,
        from: City,
        to: City,
        mode: String,
        compute: () -> List<HexCoord>
    ): List<HexCoord> {
        val key = "${seller.gameInfo.gameId}|$mode|${from.id}|${to.id}"
        synchronized(cache) {
            cache[key]?.let { return it }
            val route = compute()
            if (cache.size >= MaxCacheSize) cache.remove(cache.keys.first())
            cache[key] = route
            return route
        }
    }

    private fun search(
        starts: List<Tile>,
        targets: Set<Tile>,
        passable: (Tile) -> Boolean
    ): List<HexCoord> {
        if (starts.isEmpty() || targets.isEmpty()) return emptyList()
        val queue = ArrayDeque<Tile>()
        val parent = HashMap<Tile, Tile?>()
        for (start in starts) {
            if (!passable(start) || parent.containsKey(start)) continue
            parent[start] = null
            queue.addLast(start)
        }
        while (queue.isNotEmpty()) {
            val tile = queue.removeFirst()
            if (tile in targets) {
                val result = ArrayList<HexCoord>()
                var current: Tile? = tile
                while (current != null) {
                    result.add(current.position)
                    current = parent[current]
                }
                result.reverse()
                return result
            }
            if (parent.size >= MaxVisitedTiles) return emptyList()
            for (next in tile.neighbors) {
                if (!passable(next) || parent.containsKey(next)) continue
                parent[next] = tile
                queue.addLast(next)
            }
        }
        return emptyList()
    }
}
