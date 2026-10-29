package com.unciv.logic.market

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unit.BaseUnit
import java.util.UUID

/**
 * Grand Strategy military production market (phase 1).
 *
 * Offers are calculated only when the player opens the market: there is no
 * full-map scan in the game render loop or AI turn. Orders use genuine city
 * production and a prepaid escrow, which is paid to the seller only on delivery.
 * Phase 1: player orders from AI producers; AI buyers and manual player offers
 * are intentionally deferred.
 */
object MilitaryMarket {

    data class Offer(val sellerId: String, val cityId: String, val unitName: String, val price: Int)

    private fun sellerFor(buyer: Civilization, seller: Civilization): Boolean =
        buyer != seller && buyer.isHuman() && seller.isAI() &&
            seller.isMajorCiv() && !buyer.isDefeated() && !seller.isDefeated() &&
            buyer.knows(seller) && seller.knows(buyer) &&
            !buyer.isAtWarWith(seller) &&
            buyer.getDiplomacyManager(seller) != null &&
            seller.getDiplomacyManager(buyer) != null

    private fun eligibleCity(city: City): Boolean =
        !city.isPuppet && !city.isInResistance() && !city.isBeingRazed &&
            city.cityConstructions.constructionQueue.size < 10 &&
            city.civ.gameInfo.militaryMarketContracts.none { it.sellerCityId == city.id }

    private fun price(seller: Civilization, city: City, unit: BaseUnit): Int =
        (unit.getProductionCost(seller, city).toLong() * 3L)
            .coerceIn(60L, 100000L).toInt()

    /** Each AI advertises military units from its highest-production eligible city. */
    fun offersFor(buyer: Civilization): List<Offer> {
        if (!buyer.isHuman() || buyer.isDefeated() || buyer.cities.isEmpty()) return emptyList()
        val game = buyer.gameInfo
        return game.civilizations.asSequence()
            .filter { sellerFor(buyer, it) }
            .mapNotNull { seller ->
                seller.cities.filter { eligibleCity(it) }
                    .maxByOrNull { it.cityStats.currentCityStats.production }
                    ?.let { seller to it }
            }
            .flatMap { (seller, city) ->
                city.cityConstructions.getConstructableUnits()
                    .filter { it.isMilitary && !it.isGreatPerson }
                    .map { unit -> Offer(seller.civID, city.id, unit.name, price(seller, city, unit)) }
            }
            .sortedWith(compareBy<Offer> { it.sellerId }.thenBy { it.price }.thenBy { it.unitName })
            .take(100) // Keep the overview responsive on late-game saves.
            .toList()
    }

    /** Revalidate every condition when the button is clicked, not only on UI render. */
    fun order(buyer: Civilization, offer: Offer): Boolean {
        val game = buyer.gameInfo
        val seller = game.getCivilizationOrNull(offer.sellerId) ?: return false
        if (!sellerFor(buyer, seller)) return false
        val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: return false
        if (!eligibleCity(city)) return false
        val unit = game.ruleset.units[offer.unitName] ?: return false
        if (!unit.isMilitary || unit.isGreatPerson || !city.cityConstructions.canAddToQueue(unit)) return false
        if (city.cityConstructions.getConstructableUnits().none { it.name == unit.name }) return false

        val goldPrice = price(seller, city, unit)
        if (buyer.gold < goldPrice || goldPrice <= 0) return false

        // addToQueue can reject an order in edge cases; verify it added a copy.
        val before = city.cityConstructions.constructionQueue.count { it == unit.name }
        city.cityConstructions.addToQueue(unit, addToTop = true)
        val after = city.cityConstructions.constructionQueue.count { it == unit.name }
        if (after != before + 1) return false

        buyer.addGold(-goldPrice)
        game.militaryMarketContracts.add(MilitaryMarketContract(
            UUID.randomUUID().toString(), seller.civID, city.id,
            buyer.civID, unit.name, goldPrice, game.turns
        ))
        buyer.addNotification(
            "군수 시장: [${seller.civName}]에 [${unit.name}] 생산을 발주했습니다. (-${goldPrice}G 예치)",
            NotificationCategory.Trade, NotificationIcon.Trade, seller.civName
        )
        return true
    }

    fun myContracts(buyer: Civilization) = buyer.gameInfo.militaryMarketContracts
        .filter { it.buyerCivId == buyer.civID }

    /** Refund escrow if a city or a production order is no longer valid. */
    fun reconcile(game: GameInfo) {
        val snapshot = game.militaryMarketContracts.toList()
        for (contract in snapshot) {
            val seller = game.getCivilizationOrNull(contract.sellerCivId)
            val buyer = game.getCivilizationOrNull(contract.buyerCivId)
            val city = seller?.cities?.firstOrNull { it.id == contract.sellerCityId }
            val valid = buyer != null && !buyer.isDefeated() && buyer.cities.isNotEmpty() &&
                seller != null && !seller.isDefeated() && city != null &&
                !buyer.isAtWarWith(seller) &&
                contract.unitName in city.cityConstructions.constructionQueue
            if (!valid) refund(game, contract, "생산 불가로 취소")
        }
    }

    /** Optional cancellation by the human buyer. */
    fun cancel(buyer: Civilization, contractId: String): Boolean {
        val game = buyer.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.id == contractId && it.buyerCivId == buyer.civID
        } ?: return false
        val seller = game.getCivilizationOrNull(contract.sellerCivId)
        val city = seller?.cities?.firstOrNull { it.id == contract.sellerCityId }
        val queueIndex = city?.cityConstructions?.constructionQueue?.indexOf(contract.unitName) ?: -1
        if (queueIndex >= 0) city!!.cityConstructions.removeFromQueue(queueIndex, false)
        refund(game, contract, "구매자 취소")
        return true
    }

    private fun log(game: GameInfo, contract: MilitaryMarketContract, status: String) {
        game.militaryMarketHistory.add(MilitaryMarketHistoryEntry(
            contract.sellerCivId, contract.buyerCivId,
            contract.unitName, contract.price, game.turns, status
        ))
        while (game.militaryMarketHistory.size > 100) game.militaryMarketHistory.removeAt(0)
    }

    private fun refund(game: GameInfo, contract: MilitaryMarketContract, reason: String) {
        if (!game.militaryMarketContracts.remove(contract)) return
        val buyer = game.getCivilizationOrNull(contract.buyerCivId)
        buyer?.addGold(contract.price)
        buyer?.addNotification(
            "군수 시장: [${contract.unitName}] 주문이 ${reason}되었습니다. (+${contract.price}G 환불)",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        log(game, contract, "환불")
    }

    /** Called only after an actual city has finished producing a unit. */
    fun onProduced(city: City, producedUnit: MapUnit) {
        val game = city.civ.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.sellerCivId == city.civ.civID && it.sellerCityId == city.id &&
                it.unitName == producedUnit.baseUnit.name
        } ?: return

        val buyer = game.getCivilizationOrNull(contract.buyerCivId)
        if (buyer == null || buyer.isDefeated() || buyer.cities.isEmpty() ||
            buyer.isAtWarWith(city.civ)) {
            // The seller keeps the unit; the buyer recovers escrow.
            refund(game, contract, "인도 불가로 취소")
            return
        }

        val received = buyer.units.addUnit(producedUnit.baseUnit)
        if (received == null) {
            // No valid spawn tile (e.g. no port for a naval unit).
            refund(game, contract, "배치 공간 부족으로 취소")
            return
        }

        // One seller unit is replaced with exactly one buyer unit.
        // Apply movement penalty to prevent instant imported-unit actions.
        received.currentMovement = 0f
        producedUnit.destroy()
        city.civ.addGold(contract.price)
        game.militaryMarketContracts.remove(contract)
        log(game, contract, "인도 완료")

        buyer.addNotification(
            "군수 시장: [${city.civ.civName}]에서 [${contract.unitName}] 1기를 인도받았습니다.",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        city.civ.addNotification(
            "군수 시장: [${contract.unitName}] 납품 완료 (+${contract.price}G)",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
    }
}
