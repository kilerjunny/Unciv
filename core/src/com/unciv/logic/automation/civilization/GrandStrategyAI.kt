package com.unciv.logic.automation.civilization

import yairm210.purity.annotations.Readonly
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.ThreatLevel
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus

/** Read-only strategic assessment. v0.9 does not change vanilla AI decisions yet. */
internal object GrandStrategyAI {
    enum class Priority { EconomicRecovery, Security, Diplomacy, Expansion }

    fun strongestKnownThreat(civ: Civilization): Civilization? {
        val rivals = civ.getKnownCivs().filter {
            it.isMajorCiv() && it.isAlive() && it != civ
        }
        return rivals.firstOrNull {
            Automation.threatAssessment(civ, it) == ThreatLevel.VeryHigh
        } ?: rivals.firstOrNull {
            Automation.threatAssessment(civ, it) == ThreatLevel.High
        }
    }

    @Readonly
    fun hasCommonThreat(civ: Civilization, partner: Civilization): Boolean {
        val threat = strongestKnownThreat(civ) ?: return false
        if (threat == partner || threat == civ) return false
        if (!partner.knows(threat)) return false
        return strongestKnownThreat(partner) == threat
    }

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
