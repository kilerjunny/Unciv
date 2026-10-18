package com.unciv.logic.automation.civilization

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus

/** Read-only strategic assessment. v0.9 does not change vanilla AI decisions yet. */
internal object GrandStrategyAI {
    enum class Priority { EconomicRecovery, Security, Diplomacy, Expansion }

    fun assess(civ: Civilization): Priority {
        val income = civ.stats.statsForNextTurn.gold
        if (civ.gold < 0 || (civ.gold < 100 && income < 0f)) return Priority.EconomicRecovery
        val atWar = civ.diplomacy.values.any {
            it.otherCiv.isMajorCiv() && it.diplomaticStatus == DiplomaticStatus.War
        }
        if (atWar) return Priority.Security
        val knownMajorCivs = civ.getKnownCivs().count { it.isMajorCiv() && it.isAlive() }
        if (knownMajorCivs > 0 && civ.cities.size <= 2) return Priority.Diplomacy
        return Priority.Expansion
    }
}
