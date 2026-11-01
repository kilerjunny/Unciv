package com.unciv.logic.market

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile

/**
 * GS_TERRITORY_TOLLS_SUPPLY_V1
 * OBSERVATION ONLY: all amounts are abstract routing indices, not gold.
 * No real tariff revenue, diplomatic access restrictions, cargo or treasury changes.
 *
 * The same step formula is used during weighted search and after route selection,
 * so the recorded breakdown adds up to the selected path cost.
 */
object WorldMarketTerritoryCosts {
    data class Breakdown(
        val terrain: Int,
        val mainSaving: Int,
        val supply: Int,
        val toll: Int,
        val port: Int
    ) {
        val total: Int get() = terrain - mainSaving + supply + toll + port
    }

    private const val InlandStep = 12
    private const val CoastalStep = 7
    private const val OpenOceanStep = 4
    private const val PortTransfer = 16

    private fun terrain(tile: Tile): Int = when {
        tile.isLand -> InlandStep
        tile.isOcean -> OpenOceanStep
        else -> CoastalStep
    }

    private fun terrainStep(from: Tile, to: Tile): Int =
        (terrain(from) + terrain(to) + 1) / 2

    private fun mainSaving(base: Int, onMain: Boolean): Int =
        if (onMain) base - ((base * 3 + 3) / 4).coerceAtLeast(1) else 0

    private fun friendly(seller: Civilization, owner: Civilization): Boolean =
        seller.getDiplomacyManager(owner)?.isConsideredFriendlyTerritory() == true

    // A supply surcharge is charged for each entered tile (in addition to its
    // existing terrain cost). Roads lower that surcharge, never below one.
    private fun supplyStep(to: Tile, seller: Civilization, buyer: Civilization): Int {
        if (!to.isLand) return 1 // at-sea provisioning
        val owner = to.getOwner()
        val base = when {
            owner == null -> 7 // isolated wilderness, no local logistics
            owner == seller -> 2
            owner == buyer -> 3
            seller.isAtWarWith(owner) || buyer.isAtWarWith(owner) -> 8
            friendly(seller, owner) -> 3
            else -> 5
        }
        return (base - if (to.getUnpillagedRoad() != RoadStatus.None) 1 else 0)
            .coerceAtLeast(1)
    }

    // Border toll is assessed upon *entering* an external land territory,
    // not on every tile. A water-to-land arrival counts as a new entry even if
    // coastal water happens to share the harbor city's tile ownership.
    private fun tollStep(from: Tile, to: Tile, seller: Civilization, buyer: Civilization): Int {
        if (!to.isLand) return 0
        val owner = to.getOwner() ?: return 0
        if (owner == seller) return 0
        if (from.isLand && from.getOwner() == owner) return 0
        return when {
            owner == buyer -> 6
            seller.isAtWarWith(owner) || buyer.isAtWarWith(owner) -> 60
            friendly(seller, owner) -> 7
            else -> 18
        }
    }

    private fun portStep(from: Tile, to: Tile, seller: Civilization, buyer: Civilization): Int {
        if (from.isLand == to.isLand) return 0
        val harbor = if (from.isLand) from else to
        val owner = harbor.getOwner()
        val fee = when {
            owner == null || owner == seller -> 0
            owner == buyer -> 3
            seller.isAtWarWith(owner) || buyer.isAtWarWith(owner) -> 20
            friendly(seller, owner) -> 3
            else -> 8
        }
        return PortTransfer + fee
    }

    fun stepCost(
        from: Tile, to: Tile, seller: Civilization, buyer: Civilization, onMain: Boolean
    ): Int {
        val base = terrainStep(from, to)
        return base - mainSaving(base, onMain) + supplyStep(to, seller, buyer) +
            tollStep(from, to, seller, buyer) + portStep(from, to, seller, buyer)
    }

    fun breakdown(
        tiles: List<Tile>, seller: Civilization, buyer: Civilization,
        onMain: (Tile, Tile) -> Boolean
    ): Breakdown {
        var terrain = 0
        var saving = 0
        var supply = 0
        var toll = 0
        var port = 0
        for (i in 0 until tiles.size - 1) {
            val from = tiles[i]
            val to = tiles[i + 1]
            val base = terrainStep(from, to)
            terrain += base
            saving += mainSaving(base, onMain(from, to))
            supply += supplyStep(to, seller, buyer)
            toll += tollStep(from, to, seller, buyer)
            port += portStep(from, to, seller, buyer)
        }
        return Breakdown(terrain, saving, supply, toll, port)
    }
}
