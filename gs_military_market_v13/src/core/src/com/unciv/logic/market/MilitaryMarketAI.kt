package com.unciv.logic.market

import com.unciv.logic.civilization.Civilization

/** Light AI participation. Called once per AI turn, never per rendered frame. */
object MilitaryMarketAI {
    private const val MIN_GOLD_RESERVE = 300
    private const val COOLDOWN_TURNS = 12
    private const val MAX_ACTIVE_CONTRACTS = 24

    fun processTurn(civ: Civilization) {
        if (!civ.isAI() || !civ.isMajorCiv() || civ.isDefeated()) return
        val game = civ.gameInfo

        // AI buyers accept delivered units automatically. Human buyers decide manually.
        for (contract in MilitaryMarket.myContracts(civ).toList()) {
            if (contract.status == MilitaryMarket.AWAITING_RECEIPT &&
                MilitaryMarket.canReceive(civ, contract)) {
                MilitaryMarket.receive(civ, contract.id)
            }
        }
        // A human supplier MUST release a unit manually. AI suppliers already release on completion.
        for (contract in MilitaryMarket.sellingContracts(civ)) {
            if (contract.status == MilitaryMarket.AWAITING_DISPATCH) {
                MilitaryMarket.dispatch(civ, contract.id)
            }
        }

        // Avoid expensive market scans during ordinary AI movement and discourage spam.
        if (game.militaryMarketContracts.size >= MAX_ACTIVE_CONTRACTS || civ.cities.isEmpty()) return
        if (MilitaryMarket.myContracts(civ).isNotEmpty()) return
        val last = game.militaryMarketAILastOrderTurn[civ.civID] ?: -1000
        if (game.turns - last < COOLDOWN_TURNS || civ.gold < MIN_GOLD_RESERVE) return

        val atWar = civ.isAtWar()
        val militaryCount = civ.units.getCivUnits().count { it.isMilitary() }
        if (!atWar && militaryCount >= civ.cities.size * 3) return
        val budget = if (atWar) civ.gold / 3 else civ.gold / 4
        if (budget <= 0) return
        val hasNavalBase = civ.cities.any { it.isNaval() }
        val candidates = MilitaryMarket.offersFor(civ, limit = 100)
            .asSequence()
            .filter { it.price in 1..budget }
            .mapNotNull { offer ->
                val seller = game.getCivilizationOrNull(offer.sellerId) ?: return@mapNotNull null
                val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: return@mapNotNull null
                val unit = game.ruleset.units[offer.unitName] ?: return@mapNotNull null
                // Production city is in seller territory: without a transit treaty delivery is blocked.
                if (!civ.diplomacyFunctions.canPassThroughTiles(seller)) return@mapNotNull null
                if (unit.isWaterUnit && !hasNavalBase) return@mapNotNull null
                // Don't rely on AI understanding a force that cannot fight.
                if (!unit.isMilitary || unit.getForceEvaluation() <= 0) return@mapNotNull null
                // Prefer player supply if both options are comparable; still allow AI-AI orders.
                val buyerPreference = if (seller.isHuman()) 5 else 0
                val score = unit.getForceEvaluation().toDouble() / offer.price * 100 + buyerPreference
                score to offer
            }
            .sortedByDescending { it.first }
            .take(5)
            .toList()
        for ((_, offer) in candidates) {
            if (MilitaryMarket.order(civ, offer)) {
                game.militaryMarketAILastOrderTurn[civ.civID] = game.turns
                return
            }
        }
    }
}
