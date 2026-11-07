package com.unciv.logic.automation.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import java.util.WeakHashMap

/**
 * GS_STRATEGIC_DEPLOYMENT_PC_V1
 * PC branch peacetime defensive deployment for AI major civilizations.
 *
 * Assign a bounded number of garrisons to capitals, border cities and
 * overseas cities. Units not assigned a defensive slot use ordinary Unciv AI.
 * Plans are transient, derived from the current map, and renewed every 5 turns.
 * The plan stores IDs only (no tile/unit references or save-file changes).
 */
object StrategicDeploymentAI {
    private const val PLAN_INTERVAL = 5
    private const val MAX_REACH_CHECKS_PER_DECISION = 3

    private data class Goal(
        val city: City,
        val priority: Int,
        val landSlots: Int,
        val navalSlots: Int,
        val continent: Int
    )

    private class Plan(
        val turn: Int,
        val cityCount: Int,
        val assignments: Map<Int, String>
    ) {
        // Failed paths are only remembered until the next 5-turn plan refresh.
        val unreachable = HashSet<Int>()
    }

    // Weak keys prevent leaked plans when a GameInfo is discarded or cloned.
    private val plans = WeakHashMap<Civilization, Plan>()

    private fun eligible(unit: MapUnit): Boolean =
        unit.isMilitary() && !unit.isDestroyed && unit.id >= 0 &&
            !unit.baseUnit.isAirUnit() && !unit.isNuclearWeapon() &&
            !unit.isTransported && !unit.isEscorting() &&
            !unit.hasUnique(UniqueType.FoundCity) &&
            !unit.hasUnique(UniqueType.BuildImprovements) &&
            !unit.isExploring()

    @Synchronized
    private fun planFor(civ: Civilization): Plan {
        val turn = civ.gameInfo.turns
        val old = plans[civ]
        if (old != null && turn >= old.turn && turn - old.turn < PLAN_INTERVAL &&
            old.cityCount == civ.cities.size) return old

        val capital = civ.getCapital()?.getCenterTile()
        val capitalContinent = capital?.getContinent() ?: -1
        val goals = civ.cities.map { city ->
            val center = city.getCenterTile()
            val continent = center.getContinent()
            val overseas = capitalContinent >= 0 && continent >= 0 &&
                continent != capitalContinent
            val border = center.getTilesInDistance(5).any { tile ->
                val owner = tile.getOwner()
                owner != null && owner != civ && owner.isMajorCiv()
            }
            val priority = (if (city.isCapital()) 30 else 0) +
                (if (border) 35 else 0) +
                (if (overseas) 25 else 0) +
                (if (city.isNaval()) 10 else 0)
            val landSlots = if (border) 3 else if (city.isCapital() || overseas) 2 else 1
            val navalSlots = if (city.isNaval()) {
                if (border || overseas) 2 else 1
            } else 0
            Goal(city, priority, landSlots, navalSlots, continent)
        }.sortedWith(compareByDescending<Goal> { it.priority }.thenBy { it.city.id })

        val military = civ.units.getCivUnits().filter(::eligible).toList()
        val land = military.filterNot { it.baseUnit.isWaterUnit }.toMutableList()
        val naval = military.filter { it.baseUnit.isWaterUnit }.toMutableList()
        val assigned = HashMap<Int, String>()

        // Assign scarce units to important goals first, preferring units already
        // nearby or on the same continent. No path search during this planning pass.
        fun assignTo(goal: Goal, pool: MutableList<MapUnit>, slots: Int) {
            repeat(slots) {
                val center = goal.city.getCenterTile()
                val closest = pool.minWithOrNull(compareBy<MapUnit> { unit ->
                    val otherContinent = !unit.baseUnit.isWaterUnit &&
                        goal.continent >= 0 && unit.currentTile.getContinent() >= 0 &&
                        unit.currentTile.getContinent() != goal.continent
                    unit.currentTile.aerialDistanceTo(center) + if (otherContinent) 15 else 0
                }.thenBy { it.id }) ?: return@repeat
                assigned[closest.id] = goal.city.id
                pool.remove(closest)
            }
        }
        for (goal in goals) assignTo(goal, land, goal.landSlots)
        for (goal in goals) assignTo(goal, naval, goal.navalSlots)

        val plan = Plan(turn, civ.cities.size, assigned)
        plans[civ] = plan
        return plan
    }

    /**
     * Return true when a defensive assignment handled the unit's decision.
     * Combat, healing and hostile-camp behavior are checked earlier by
     * UnitAutomation, and any wartime uses the stock military AI.
     */
    fun tryDeploy(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (!civ.isAI() || !civ.isMajorCiv() || civ.isAtWar() ||
            !eligible(unit) || civ.cities.isEmpty()) return false
        val plan = planFor(civ)
        val destinationCityId = plan.assignments[unit.id] ?: return false
        val city = civ.cities.firstOrNull { it.id == destinationCityId } ?: return false
        val center = city.getCenterTile()
        val origin = unit.currentTile
        val naval = unit.baseUnit.isWaterUnit

        // A garrison in the assigned city's local area holds position. For land
        // units, fill the city centre first when it is empty, then guard nearby.
        val inArea = origin.aerialDistanceTo(center) <= 2 &&
            (origin.getOwner() == civ || naval && origin.getOwner() == null) &&
            (if (naval) origin.isWater else origin.isLand)
        val unoccupiedLandGarrison = !naval && center.militaryUnit == null
        if (inArea && !unoccupiedLandGarrison) {
            return true
        }
        if (!unit.hasMovement() || unit.id in plan.unreachable) return false

        val destinations = center.getTilesInDistance(2)
            .filter { tile ->
                !tile.isImpassible() &&
                    (tile.militaryUnit == null || tile.militaryUnit == unit) &&
                    (if (naval) tile.isWater &&
                        (tile.getOwner() == civ || tile.getOwner() == null)
                    else tile.isLand && tile.getOwner() == civ)
            }
            .sortedWith(compareBy<Tile>({ if (it == center) 0 else 1 },
                { it.aerialDistanceTo(origin) }, { it.aerialDistanceTo(center) }))
            .toList()

        var checks = 0
        for (tile in destinations) {
            if (tile == origin || !unit.movement.canMoveTo(tile)) continue
            if (checks >= MAX_REACH_CHECKS_PER_DECISION) break
            checks++
            if (!unit.movement.canReach(tile)) continue
            try {
                val before = unit.currentTile
                unit.movement.headTowards(tile)
                if (unit.currentTile != before) {
                    return true
                }
            } catch (_: UnitMovement.UnreachableDestinationException) {
                // Tile occupancy may change between path query and movement.
            }
        }
        // Don't repeatedly search unreachable overseas or blocked goals.
        plan.unreachable.add(unit.id)
        return false // Existing AI may find a different useful action.
    }
}
