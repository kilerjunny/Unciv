package grandstrategy.rankings

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.ui.screens.victoryscreen.RankingType

/** Extra, display-only military power statistics. The vanilla Force metric and AI stay unchanged. */
object GrandStrategyMilitaryRankings {
    /**
     * Missiles are deliberate *weapon units*, not missile cruisers, missile infantry or
     * nuclear-powered warships. Mods may extend this whitelist in future updates.
     */
    private val missileNames = setOf(
        "guided missile", "cruise missile", "ballistic missile", "icbm",
        "순항미사일", "탄도미사일", "유도미사일", "대륙간탄도미사일"
    )

    fun classification(unit: MapUnit): RankingType? {
        val name = unit.name.trim().lowercase()
        val explicitMissile = name in missileNames ||
            (unit.baseUnit.isAirUnit() && ("missile" in name || "미사일" in name))
        // Nuclear bombs and missiles are often strength-zero ("civilian" by vanilla rules).
        if (unit.isNuclearWeapon() || explicitMissile) return RankingType.AsymmetricForce
        if (!unit.isMilitary()) return null
        return when {
            unit.baseUnit.isWaterUnit -> RankingType.NavyForce
            unit.baseUnit.isAirUnit() -> RankingType.AirForce
            else -> RankingType.ArmyForce
        }
    }

    fun power(civ: Civilization, category: RankingType): Int = civ.units.getCivUnits()
        .filter { classification(it) == category }
        // Some missile/nuclear units have no conventional combat strength.
        // Count each existing weapon at least once, without inventing a nuclear blast multiplier.
        .sumOf { maxOf(1, it.getForceEvaluation()) }
}
