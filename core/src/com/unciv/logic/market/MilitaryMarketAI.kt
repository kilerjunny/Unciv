package com.unciv.logic.market

import com.unciv.logic.civilization.Civilization

/** Light AI participation. Called once per AI turn, never per rendered frame. */
object MilitaryMarketAI {
    // Procurement is throttled by cooldown, one active order per buyer, and a
    // global cap; reserve cash separately to avoid starving city-state economies.
    private const val COOLDOWN_TURNS = 6
    private const val MAX_ACTIVE_CONTRACTS = 32

    private fun reserveFor(civ: Civilization): Int = if (civ.isCityState) 25 else 50

    private fun hasDemand(civ: Civilization): Boolean {
        if (civ.isAtWar()) return true
        val soldiers = civ.units.getCivUnits().count { it.isMilitary() }
        // Relaxed peacetime ceiling; city-states with one city can still import.
        return soldiers < maxOf(8, civ.cities.size * 8)
    }

    /** AI avoids spending its entire treasury, but can purchase cheaper equipment. */
    private fun purchaseBudget(civ: Civilization, atWar: Boolean): Int {
        val spare = (civ.gold - reserveFor(civ)).coerceAtLeast(0)
        // More willing to import while retaining a minimum liquidity cushion.
        val fraction = if (atWar) (civ.gold.toLong() * 9 / 10).toInt()
            else (civ.gold.toLong() * 4 / 5).toInt()
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
        if (!civ.isAI() || !(civ.isMajorCiv() || civ.isCityState) || civ.isDefeated() ||
            civ.cities.isEmpty() || civ.gold <= 0) return 0
        val game = civ.gameInfo
        if (game.militaryMarketContracts.size >= MAX_ACTIVE_CONTRACTS ||
            MilitaryMarket.myContracts(civ).isNotEmpty()) return 0
        val last = game.militaryMarketAILastOrderTurn[civ.civID] ?: -1000
        if (game.turns - last < COOLDOWN_TURNS) return 0
        val atWar = civ.isAtWar()
        if (!hasDemand(civ)) return 0
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
        // Target is the minimum treasury that satisfies both the cash reserve
        // and the 80% peaceful / 90% wartime purchase-budget rule.
        val target = if (atWar)
            maxOf(cheapestPrice + reserveFor(civ), (cheapestPrice * 10 + 8) / 9)
        else
            maxOf(cheapestPrice + reserveFor(civ), (cheapestPrice * 5 + 3) / 4)
        return minOf(civ.gold, target)
    }

    fun processTurn(civ: Civilization) {
        if (!civ.isAI() || !(civ.isMajorCiv() || civ.isCityState) || civ.isDefeated()) return
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
        if (game.turns - last < COOLDOWN_TURNS || civ.gold <= reserveFor(civ)) return

        val atWar = civ.isAtWar()
        if (!hasDemand(civ)) return
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
                val cityStateBonus = if (seller.isCityState) 12 else 0
                val score = unit.getForceEvaluation().toDouble() / offer.price * 100 +
                    buyerPreference + uniqueBonus + cityStateBonus
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
        var cityStateCandidates = 0
        for (buyer in game.civilizations) {
            if (!buyer.isAI() || !(buyer.isMajorCiv() || buyer.isCityState) || buyer.isDefeated() ||
                buyer.cities.isEmpty() || !buyer.knows(seller) || !seller.knows(buyer) ||
                buyer.isAtWarWith(seller)) continue
            known++
            // This is necessary for ownership transfer at the seller's actual production base.
            if (!buyer.diplomacyFunctions.canPassThroughTiles(seller)) continue
            passage++
            if (buyer.gold <= reserveFor(buyer)) continue
            val atWar = buyer.isAtWar()
            val budget = purchaseBudget(buyer, atWar)
            if (listings.none { it.price in 1..budget }) continue
            affordable++
            if (game.militaryMarketContracts.size >= MAX_ACTIVE_CONTRACTS ||
                MilitaryMarket.myContracts(buyer).isNotEmpty()) continue
            val last = game.militaryMarketAILastOrderTurn[buyer.civID] ?: -1000
            if (game.turns - last < COOLDOWN_TURNS) continue
            if (!hasDemand(buyer)) continue
            val feasible = listings.any { listing ->
                val city = seller.cities.firstOrNull { it.id == listing.sellerCityId }
                val unit = game.ruleset.units[listing.unitName]
                listing.price in 1..budget && city != null && unit != null &&
                    city.cityConstructions.canAddToQueue(unit) &&
                    listing.unitName !in city.cityConstructions.constructionQueue
            }
            if (feasible) {
                candidates++
                if (buyer.isCityState) cityStateCandidates++
            }
        }
        return "AI 구매 진단: 외교 가능 ${known}국 · 통행 가능 ${passage}국 · " +
            "예산 충족 ${affordable}국 · 현재 발주 후보 ${candidates}국 " +
            "(도시국가 ${cityStateCandidates}국 포함)"
    }
}
