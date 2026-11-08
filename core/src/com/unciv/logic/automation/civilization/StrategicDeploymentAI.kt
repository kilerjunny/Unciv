package com.unciv.logic.automation.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.market.MilitaryMarket
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import java.util.WeakHashMap

/**
 * GS_STRATEGIC_DEPLOYMENT_AI_V2
 * Peacetime deployment for AI major civilizations. Existing land garrison slots
 * are retained; EVERY eligible naval unit gets a friendly naval-base objective
 * even after the normal guard slots have been filled.
 *
 * Plans keep only unit and city IDs, not live game objects, and refresh after
 * five turns. Each unreachable naval port is attempted at most once per plan,
 * with a different friendly port tried on a later turn. No teleportation.
 */
object StrategicDeploymentAI {
    private const val PLAN_INTERVAL = 5
    private const val MAX_LAND_REACH_CHECKS = 3
    private const val MAX_NAVAL_REACH_CHECKS = 2
    private const val NAVAL_GUARD_RADIUS = 2
    private const val NAVAL_RESERVE_RADIUS = 4

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
        val assignments: Map<Int, String>,
        val navalPortOrder: Map<Int, List<String>>,
        val navalGuards: Set<Int>
    ) {
        // Old land path behavior: don't repeat expensive checks during a plan.
        val unreachableLand = HashSet<Int>()
        // Navy: a failed port is skipped, but another port may be tried next turn.
        val failedNavalPorts = HashMap<Int, MutableSet<String>>()
    }

    // Weak keys + values containing IDs only avoid retaining discarded games.
    private val plans = WeakHashMap<Civilization, Plan>()

    private fun eligible(unit: MapUnit): Boolean =
        unit.isMilitary() && !unit.isDestroyed && unit.id >= 0 &&
            !unit.baseUnit.isAirUnit() && !unit.isNuclearWeapon() &&
            !unit.isTransported && !unit.isEscorting() &&
            !unit.hasUnique(UniqueType.FoundCity) &&
            !unit.hasUnique(UniqueType.BuildImprovements) &&
            (!unit.isExploring() || unit.baseUnit.isWaterUnit) &&
            !MilitaryMarket.isReservedUnit(unit)

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

        // Preserve the v1 land and principal-port garrison priorities.
        // The planning pass itself does not calculate a single movement path.
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

        // After guard slots, remaining ships still need a peacetime base.
        // Spread reserves among nearby naval cities instead of sending everyone
        // to the capital. Overseas ports remain legitimate permanent stations.
        val navalGuards = military.asSequence()
            .filter { it.baseUnit.isWaterUnit && it.id in assigned }
            .map { it.id }.toHashSet()
        val navalGoals = goals.filter { it.navalSlots > 0 }
        val reserveLoads = HashMap<String, Int>()
        for (ship in naval) {
            val goal = navalGoals.minWithOrNull(compareBy<Goal> { port ->
                val distance = ship.currentTile.aerialDistanceTo(port.city.getCenterTile())
                distance * 4 + (reserveLoads[port.city.id] ?: 0) * 5 - port.priority / 3
            }.thenBy { it.city.id }) ?: continue // No owned harbor; use stock AI.
            assigned[ship.id] = goal.city.id
            reserveLoads[goal.city.id] = (reserveLoads[goal.city.id] ?: 0) + 1
        }

        // Alternative port IDs are calculated ONCE per plan, not every AI turn.
        // Original guard or reserve assignment is always first.
        val portOrder = HashMap<Int, List<String>>()
        for (ship in military) {
            if (!ship.baseUnit.isWaterUnit) continue
            val preferred = assigned[ship.id] ?: continue
            val alternatives = navalGoals.sortedWith(compareBy<Goal> { port ->
                ship.currentTile.aerialDistanceTo(port.city.getCenterTile()) - port.priority / 8
            }.thenBy { it.city.id }).map { it.city.id }
            portOrder[ship.id] = listOf(preferred) + alternatives.filter { it != preferred }
        }

        val plan = Plan(turn, civ.cities.size, assigned, portOrder, navalGuards)
        plans[civ] = plan
        GrandStrategyPerf.addCount("strategic_deploy_replans", 1)
        GrandStrategyPerf.addCount("strategic_deploy_assigned", assigned.size)
        GrandStrategyPerf.addCount("strategic_naval_assigned", portOrder.size)
        return plan
    }

    /** Return true when a defensive assignment handled the unit's decision. */
    fun tryDeploy(unit: MapUnit): Boolean {
        val civ = unit.civ
        if (!civ.isAI() || !civ.isMajorCiv() || civ.isAtWar() ||
            !eligible(unit) || civ.cities.isEmpty()) return false
        val plan = planFor(civ)
        val naval = unit.baseUnit.isWaterUnit
        val destinationCityId = if (naval) {
            val failed = plan.failedNavalPorts.getOrPut(unit.id) { HashSet() }
            plan.navalPortOrder[unit.id]?.firstOrNull { it !in failed }
        } else plan.assignments[unit.id]
        if (destinationCityId == null) return false
        val city = civ.cities.firstOrNull { it.id == destinationCityId } ?: return false
        val center = city.getCenterTile()
        val origin = unit.currentTile

        // Reserve ships can patrol within four tiles of their assigned harbor;
        // principal guards stay closer. Avoid fleet congestion in city centers.
        val radius = if (naval) {
            if (unit.id in plan.navalGuards) NAVAL_GUARD_RADIUS else NAVAL_RESERVE_RADIUS
        } else 2
        val inArea = origin.aerialDistanceTo(center) <= radius &&
            (origin.getOwner() == civ || naval && origin.getOwner() == null) &&
            (if (naval) origin.isWater else origin.isLand)
        val unoccupiedLandGarrison = !naval && center.militaryUnit == null
        if (inArea && !unoccupiedLandGarrison) {
            GrandStrategyPerf.addCount("strategic_deploy_holding", 1)
            if (naval) GrandStrategyPerf.addCount("strategic_naval_holding", 1)
            return true
        }
        // A ship that has used its movement will retry next turn. Don't send
        // it into the stock wandering AI just because its movement is zero.
        if (!unit.hasMovement()) return naval
        if (!naval && unit.id in plan.unreachableLand) return false

        val destinations = center.getTilesInDistance(radius)
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
        val maxChecks = if (naval) MAX_NAVAL_REACH_CHECKS else MAX_LAND_REACH_CHECKS
        for (tile in destinations) {
            if (tile == origin || !unit.movement.canMoveTo(tile)) continue
            if (checks >= maxChecks) break
            checks++
            if (!unit.movement.canReach(tile)) continue
            try {
                val before = unit.currentTile
                unit.movement.headTowards(tile)
                if (unit.currentTile != before) {
                    GrandStrategyPerf.addCount("strategic_deploy_moves", 1)
                    if (naval) GrandStrategyPerf.addCount("strategic_naval_moves", 1)
                    return true
                }
            } catch (_: UnitMovement.UnreachableDestinationException) {
                // Ships and other units may change occupancy during the AI turn.
            }
        }

        if (naval) {
            // Retry a different friendly naval city next peaceful turn instead
            // of giving up on the ship for the entire planning period.
            plan.failedNavalPorts.getOrPut(unit.id) { HashSet() }.add(destinationCityId)
            GrandStrategyPerf.addCount("strategic_naval_port_failures", 1)
            return false // Keep stock AI fallback if no base was reachable.
        }
        plan.unreachableLand.add(unit.id)
        GrandStrategyPerf.addCount("strategic_deploy_unreachable", 1)
        return false
    }
}
