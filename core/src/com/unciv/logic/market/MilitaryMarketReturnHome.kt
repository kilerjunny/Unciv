package com.unciv.logic.market

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 * GS_MILITARY_MARKET_HOMEBOUND_V1
 * AI-bought military units return to the buyer's owned territory in peacetime.
 * Only units actually delivered by MilitaryMarket.receive are tracked.
 * Real movement, never teleportation; no interference with AI wartime decisions.
 * At most six full reachability checks per homebound unit per AI decision.
 */
object MilitaryMarketReturnHome {
    private const val MaxPathChecks = 6

    /** True if homebound handling took this unit's action for this decision. */
    fun tryReturn(unit: MapUnit): Boolean {
        val civ = unit.civ
        val pending = civ.gameInfo.militaryMarketReturnHomeUnits
        if (pending.isEmpty()) return false
        val unitKey = unit.id.toString()
        val assignedCiv = pending[unitKey] ?: return false
        if (assignedCiv != civ.civID || unit.isDestroyed) {
            pending.remove(unitKey)
            return false
        }
        if (!civ.isAI()) return false // A human buyer always controls purchased units.
        if (unit.getTile().getOwner() == civ) {
            pending.remove(unitKey)
            return false // Arrived: resume ordinary AI from now on.
        }
        if (civ.isAtWar()) return false // War AI takes priority; resume after peace.
        if (!unit.hasMovement()) return true // Delivery turn has zero movement points.

        val origin = unit.getTile()
        val air = unit.baseUnit.isAirUnit()
        val water = unit.baseUnit.isWaterUnit
        // Choose the nearest legal OWNED destination; naval units can use coastal
        // owned water, and air units need a buyer-owned city/airbase.
        val destinations: Sequence<Tile> = civ.cities.asSequence()
            .flatMap { city ->
                if (air) sequenceOf(city.getCenterTile())
                else city.getCenterTile().getTilesInDistance(2).asSequence()
            }
            .filter { tile ->
                tile.getOwner() == civ && !tile.isImpassible() && when {
                    air -> tile.isCityCenter()
                    water -> tile.isWater || tile.isCityCenter()
                    else -> tile.isLand
                }
            }
            .distinct()
            .sortedBy { it.aerialDistanceTo(origin) }

        var checked = 0
        for (target in destinations) {
            if (!unit.movement.canMoveTo(target)) continue
            if (checked++ >= MaxPathChecks) break
            if (!unit.movement.canReach(target)) continue
            unit.movement.headTowards(target)
            if (unit.getTile().getOwner() == civ) pending.remove(unitKey)
            return true
        }
        // No reachable home tile: never teleport or freeze all AI behavior.
        // Ordinary AI can act this turn and we will retry on the next peaceful turn.
        return false
    }
}
