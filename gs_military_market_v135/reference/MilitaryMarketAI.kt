package com.unciv.logic.market

import com.unciv.logic.civilization.Civilization

/** Light AI participation. Called once per AI turn, never per rendered frame. */
object MilitaryMarketAI {
    private const val MIN_GOLD_RESERVE = 100
    private const val COOLDOWN_TURNS = 12
    private const val MAX_ACTIVE_CONTRACTS = 24

    /** AI avoids spending its entire treasury, but can purchase cheaper equipment. */
    private fun purchaseBudget(civ: Civilization, atWar: Boolean): Int {
        val spare = (civ.gold - MIN_GOLD_RESERVE).coerceAtLeast(0)
        val fraction = if (atWar) civ.gold * 2 / 3 else civ.gold / 2
        return minOf(spare, fraction)
    }

    /**
     * Protected treasury for a REAL procurement opportunity, not an unconditional tax.
     * The target is the amount at which purchaseBudget() will allow the cheapest
     * deliverable unit. While saving, ordinary spending cannot consume this gold.
     * No viable offer, no transit rights, no demand or a cooldown => no reservation.
     * Recomputed on the AI's turn; no cache or save-file schema change.
     */
    fun protectedGold(civ: Civilization): Int {
        if (!civ.isAI() || !civ.isMajorCiv() || civ.isDefeated() ||
            civ.cities.isEmpty() || civ.gold <= 0) return 0
        val game = civ.gameInfo
        if (game.militaryMarketContracts.size >= MAX_ACTIVE_CONTRACTS ||
            MilitaryMarket.myContracts(civ).isNotEmpty()) return 0
        val last = game.militaryMarketAILastOrderTurn[civ.civID] ?: -1000
        if (game.turns - last < COOLDOWN_TURNS) return 0
        val atWar = civ.isAtWar()
        if (!atWar && civ.units.getCivUnits().count { it.isMilitary() } >= civ.cities.size * 5)
            return 0
        val hasNavalBase = civ.cities.any { it.isNaval() }
        val ceiling = if (atWar) 550 else 350
        val cheapestPrice = MilitaryMarket.offersFor(civ, limit = 100)
            .asSequence()
            .filter { it.price in 1..ceiling }
            .filter { offer ->
                val seller = game.getCivilizationOrNull(offer.sellerId) ?: return@filter false
                val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: return@filter false
                val unit = game.ruleset.units[offer.unitName] ?: return@filter false
                civ.diplomacyFunctions.canPassThroughTiles(seller) &&
                    offer.unitName !in city.cityConstructions.constructionQueue &&
                    unit.isMilitary && unit.getForceEvaluation() > 0 &&
                    (!unit.isWaterUnit || hasNavalBase)
            }
            .minOfOrNull { it.price } ?: return 0
        // These targets satisfy BOTH the 100G liquidity floor and the AI's
        // 50% peaceful / 2/3 wartime purchase-budget limit.
        val target = if (atWar)
            maxOf(cheapestPrice + MIN_GOLD_RESERVE, (cheapestPrice * 3 + 1) / 2)
        else
            maxOf(cheapestPrice + MIN_GOLD_RESERVE, cheapestPrice * 2)
        return minOf(civ.gold, target)
    }

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
        if (!atWar && militaryCount >= civ.cities.size * 5) return
        val budget = purchaseBudget(civ, atWar)
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
                val buyerPreference = if (seller.isHuman()) 150 else 0
                // Civilisation-unique units are tradable, even if the buyer cannot train them.
                val uniqueBonus = if (unit.uniqueTo != null || unit.replaces != null) 30 else 0
                val score = unit.getForceEvaluation().toDouble() / offer.price * 100 + buyerPreference + uniqueBonus
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

    /** Read-only market diagnostics for the human seller; evaluated only when the market UI opens. */
    fun sellerDemandSummary(seller: Civilization): String {
        val game = seller.gameInfo
        val listings = MilitaryMarket.myListings(seller)
        if (listings.isEmpty()) return "판매 등록 상품이 없습니다."
        var known = 0
        var passage = 0
        var affordable = 0
        var candidates = 0
        for (buyer in game.civilizations) {
            if (!buyer.isAI() || !buyer.isMajorCiv() || buyer.isDefeated() ||
                buyer.cities.isEmpty() || !buyer.knows(seller) || !seller.knows(buyer) ||
                buyer.isAtWarWith(seller)) continue
            known++
            // This is necessary for ownership transfer at the seller's actual production base.
            if (!buyer.diplomacyFunctions.canPassThroughTiles(seller)) continue
            passage++
            if (buyer.gold <= MIN_GOLD_RESERVE) continue
            val atWar = buyer.isAtWar()
            val budget = purchaseBudget(buyer, atWar)
            if (listings.none { it.price in 1..budget }) continue
            affordable++
            if (game.militaryMarketContracts.size >= MAX_ACTIVE_CONTRACTS ||
                MilitaryMarket.myContracts(buyer).isNotEmpty()) continue
            val last = game.militaryMarketAILastOrderTurn[buyer.civID] ?: -1000
            if (game.turns - last < COOLDOWN_TURNS) continue
            val militaryCount = buyer.units.getCivUnits().count { it.isMilitary() }
            if (!atWar && militaryCount >= buyer.cities.size * 5) continue
            val feasible = listings.any { listing ->
                val city = seller.cities.firstOrNull { it.id == listing.sellerCityId }
                val unit = game.ruleset.units[listing.unitName]
                listing.price in 1..budget && city != null && unit != null &&
                    city.cityConstructions.canAddToQueue(unit) &&
                    listing.unitName !in city.cityConstructions.constructionQueue
            }
            if (feasible) candidates++
        }
        return "AI 구매 진단: 외교 가능 ${known}국 · 통행 가능 ${passage}국 · " +
            "예산 충족 ${affordable}국 · 현재 발주 후보 ${candidates}국"
    }
}
