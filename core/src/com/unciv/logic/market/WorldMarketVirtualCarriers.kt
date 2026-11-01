package com.unciv.logic.market

import com.unciv.logic.GameInfo
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.logic.market.WorldMarketMainRoutes.MainTradeCorridor

/**
 * GS_VIRTUAL_MERCHANT_NETWORK_V1
 * A diagnostic view of already recorded trade paths; NO merchant units,
 * cargo inventory, changes to transactions, new path searches, or save fields.
 * A leg is a connection between two neighboring map tiles. This classification
 * does NOT count the number of physical merchant ships.
 */
object WorldMarketVirtualCarriers {
    const val OCEAN = "OCEAN_MERCHANT"
    const val COAST = "COASTAL_MERCHANT"
    const val LAND = "LAND_CARAVAN"
    const val TRANSFER = "PORT_TRANSFER"

    data class Edge(val a: HexCoord, val b: HexCoord)

    data class Summary(
        val ocean: Int,
        val coast: Int,
        val land: Int,
        val transfers: Int,
        val main: Int,
        val branches: Int,
        val unknown: Int
    ) {
        val totalSteps get() = ocean + coast + land + transfers + unknown
    }

    /** Ocean-going virtual merchants may sail coast as well, but coastal-only
     * links are assigned to coastal merchants in this first visualization.
     * A land-water edge represents one harbor transfer, not an actual vessel.
     */
    fun mode(from: Tile, to: Tile): String = when {
        from.isLand != to.isLand -> TRANSFER
        from.isLand && to.isLand -> LAND
        from.isOcean || to.isOcean -> OCEAN
        else -> COAST
    }

    fun edge(a: HexCoord, b: HexCoord): Edge =
        if (a.x < b.x || (a.x == b.x && a.y <= b.y)) Edge(a, b)
        else Edge(b, a)

    fun mainEdges(routes: List<MainTradeCorridor>): Set<Edge> {
        val result = HashSet<Edge>()
        for (corridor in routes) for (i in 0 until corridor.tiles.size - 1)
            result.add(edge(corridor.tiles[i], corridor.tiles[i + 1]))
        return result
    }

    /** Current global main corridors only. Old trade history may use routes
     * recorded before the current main-corridor network was formed. */
    fun summarize(game: GameInfo, route: List<HexCoord>, backbone: Set<Edge>): Summary {
        var ocean = 0
        var coast = 0
        var land = 0
        var transfers = 0
        var main = 0
        var branches = 0
        var unknown = 0
        for (i in 0 until route.size - 1) {
            val a = route[i]
            val b = route[i + 1]
            val first = game.tileMap.getOrNull(a.x, a.y)
            val second = game.tileMap.getOrNull(b.x, b.y)
            if (first == null || second == null) {
                unknown++
                continue
            }
            when (mode(first, second)) {
                OCEAN -> ocean++
                COAST -> coast++
                LAND -> land++
                TRANSFER -> transfers++
            }
            if (edge(a, b) in backbone) main++ else branches++
        }
        return Summary(ocean, coast, land, transfers, main, branches, unknown)
    }
}
