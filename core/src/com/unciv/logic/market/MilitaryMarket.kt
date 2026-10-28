package com.unciv.logic.market

import com.unciv.logic.GameInfo
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.unit.BaseUnit
import java.util.UUID
import yairm210.purity.annotations.Readonly

/**
 * Military procurement with two explicit handover stages.
 * Actual city production produces an ACTUAL unit at the seller's city.
 * The seller releases it (인도하기); the buyer then accepts ownership (인도받기).
 * The same physical unit remains at the production tile, with no teleportation.
 *
 * Phase 1: the player buys from AI sellers, which approve handover automatically.
 * Seller-side manual handover is exposed for future player-seller contracts.
 */
object MilitaryMarket {

    const val PRODUCING = "PRODUCING"
    const val AWAITING_DISPATCH = "AWAITING_DISPATCH"
    const val AWAITING_RECEIPT = "AWAITING_RECEIPT"

    /** Prevent AI moving a completed contract unit until the buyer receives it. */
    @Readonly
    fun isReservedUnit(unit: MapUnit): Boolean =
        unit.civ.gameInfo.militaryMarketContracts.any {
            it.sellerCivId == unit.civ.civID &&
                it.status != PRODUCING && it.producedUnitId == unit.id
        }

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
            city.civ.gameInfo.militaryMarketContracts.none {
                it.sellerCityId == city.id && it.sellerCivId == city.civ.civID
            }

    private fun price(seller: Civilization, city: City, unit: BaseUnit): Int =
        (unit.getProductionCost(seller, city).toLong() * 3L)
            .coerceIn(60L, 100000L).toInt()

    /** Advertise only when the market UI opens, not during each rendered frame. */
    fun offersFor(buyer: Civilization): List<Offer> {
        if (!buyer.isHuman() || buyer.isDefeated() || buyer.cities.isEmpty()) return emptyList()
        return buyer.gameInfo.civilizations.asSequence()
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
            .take(100).toList()
    }

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
        if (goldPrice <= 0 || buyer.gold < goldPrice) return false

        val before = city.cityConstructions.constructionQueue.count { it == unit.name }
        city.cityConstructions.addToQueue(unit, addToTop = true)
        if (city.cityConstructions.constructionQueue.count { it == unit.name } != before + 1) return false

        buyer.addGold(-goldPrice)
        game.militaryMarketContracts.add(MilitaryMarketContract(
            UUID.randomUUID().toString(), seller.civID, city.id,
            buyer.civID, unit.name, goldPrice, game.turns
        ))
        buyer.addNotification(
            "군수 시장: [${seller.civName}]에 [${unit.name}] 발주 (-${goldPrice}G 예치)",
            NotificationCategory.Trade, NotificationIcon.Trade, seller.civName
        )
        return true
    }

    fun myContracts(civ: Civilization) = civ.gameInfo.militaryMarketContracts
        .filter { it.buyerCivId == civ.civID }

    fun sellingContracts(civ: Civilization) = civ.gameInfo.militaryMarketContracts
        .filter { it.sellerCivId == civ.civID }

    fun statusLabel(contract: MilitaryMarketContract): String = when (contract.status) {
        PRODUCING -> "생산 중"
        AWAITING_DISPATCH -> "완공 · 인도하기 대기"
        AWAITING_RECEIPT -> "인도 승인됨 · 인도받기 대기"
        else -> "상태 확인 필요"
    }

    private fun producedUnit(game: GameInfo, contract: MilitaryMarketContract): MapUnit? {
        if (contract.producedUnitId < 0) return null
        val seller = game.getCivilizationOrNull(contract.sellerCivId) ?: return null
        return seller.units.getCivUnits().firstOrNull {
            it.id == contract.producedUnitId && it.baseUnit.name == contract.unitName &&
                !it.isDestroyed
        }
    }

    /** Called AFTER city construction successfully creates its unit. */
    fun onProduced(city: City, producedUnit: MapUnit) {
        val game = city.civ.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.status == PRODUCING && it.sellerCivId == city.civ.civID &&
                it.sellerCityId == city.id && it.unitName == producedUnit.baseUnit.name
        } ?: return

        contract.producedUnitId = producedUnit.id
        contract.status = AWAITING_DISPATCH

        val buyer = game.getCivilizationOrNull(contract.buyerCivId)
        buyer?.addNotification(
            "군수 시장: [${contract.unitName}] 생산 완료. 판매국의 인도를 기다립니다.",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )

        // AI sellers perform their own '인도하기' action automatically, just once.
        if (city.civ.isAI()) dispatch(city.civ, contract.id)
    }

    /** Seller's explicit release. No ownership transfer at this stage. */
    fun dispatch(seller: Civilization, contractId: String): Boolean {
        val game = seller.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.id == contractId && it.sellerCivId == seller.civID &&
                it.status == AWAITING_DISPATCH
        } ?: return false
        if (seller.isDefeated() || producedUnit(game, contract) == null) return false
        contract.status = AWAITING_RECEIPT
        game.getCivilizationOrNull(contract.buyerCivId)?.addNotification(
            "군수 시장: [${seller.civName}]이 [${contract.unitName}] 인도를 승인했습니다. 군수 시장에서 인도받기를 선택하세요.",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        return true
    }

    /** Do not move ownership to an inaccessible tile. Keep the unit at the producer's base. */
    fun canReceive(buyer: Civilization, contract: MilitaryMarketContract): Boolean {
        if (buyer.civID != contract.buyerCivId || contract.status != AWAITING_RECEIPT ||
            buyer.isDefeated() || buyer.cities.isEmpty()) return false
        val game = buyer.gameInfo
        val seller = game.getCivilizationOrNull(contract.sellerCivId) ?: return false
        if (seller.isDefeated() || buyer.isAtWarWith(seller) ||
            !buyer.knows(seller) || !seller.knows(buyer)) return false

        val unit = producedUnit(game, contract) ?: return false
        if (unit.isTransported || unit.isEscorting()) return false
        val tile = unit.getTile()
        val owner = tile.getOwner()
        // A foreign city/land without passage permission must not strand the unit.
        if (owner != null && owner != buyer &&
            !buyer.diplomacyFunctions.canPassThroughTiles(owner)) return false
        if (unit.baseUnit.isWaterUnit && tile.isLand && !tile.isCityCenter()) return false
        // Avoid ending up with a buyer land unit on ocean without embark capability.
        if (unit.baseUnit.isLandUnit && tile.isWater && !buyer.tech.unitsCanEmbark) return false
        return true
    }

    /** Buyer's explicit acceptance; physically retains the original producer tile and unit ID. */
    fun receive(buyer: Civilization, contractId: String): Boolean {
        val game = buyer.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.id == contractId && it.buyerCivId == buyer.civID
        } ?: return false
        if (!canReceive(buyer, contract)) return false
        val seller = game.getCivilizationOrNull(contract.sellerCivId) ?: return false
        val unit = producedUnit(game, contract) ?: return false

        val origin = unit.getTile()
        unit.gift(buyer) // Existing engine ownership transfer, not a newly spawned unit.
        unit.currentMovement = 0f // Buyer can depart from seller's production tile next turn.
        seller.addGold(contract.price)
        game.militaryMarketContracts.remove(contract)
        log(game, contract, "인도 완료")

        buyer.addNotification(
            "군수 시장: [${contract.unitName}] 인도받기 완료. 생산기지에서 출발하세요.",
            origin.position, NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        seller.addNotification(
            "군수 시장: [${contract.unitName}] 인도 완료 (+${contract.price}G)",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        return true
    }

    /** Check queue only during production; after completion the produced unit is authoritative. */
    fun reconcile(game: GameInfo) {
        for (contract in game.militaryMarketContracts.toList()) {
            val seller = game.getCivilizationOrNull(contract.sellerCivId)
            val buyer = game.getCivilizationOrNull(contract.buyerCivId)
            val city = seller?.cities?.firstOrNull { it.id == contract.sellerCityId }
            val valid = buyer != null && !buyer.isDefeated() && buyer.cities.isNotEmpty() &&
                seller != null && !seller.isDefeated() && city != null &&
                !buyer.isAtWarWith(seller) && when (contract.status) {
                    PRODUCING -> contract.unitName in city.cityConstructions.constructionQueue
                    AWAITING_DISPATCH, AWAITING_RECEIPT -> producedUnit(game, contract) != null
                    else -> false
                }
            if (!valid) refund(game, contract, "계약 유지 불가로 취소")
        }
    }

    /** Only unproduced orders can be cancelled unilaterally. */
    fun cancel(buyer: Civilization, contractId: String): Boolean {
        val game = buyer.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.id == contractId && it.buyerCivId == buyer.civID &&
                it.status == PRODUCING
        } ?: return false
        val seller = game.getCivilizationOrNull(contract.sellerCivId)
        val city = seller?.cities?.firstOrNull { it.id == contract.sellerCityId }
        val index = city?.cityConstructions?.constructionQueue?.indexOf(contract.unitName) ?: -1
        if (index >= 0) city!!.cityConstructions.removeFromQueue(index, false)
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
        game.getCivilizationOrNull(contract.buyerCivId)?.let { buyer ->
            buyer.addGold(contract.price)
            buyer.addNotification(
                "군수 시장: [${contract.unitName}] 주문이 ${reason}되었습니다. (+${contract.price}G 환불)",
                NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
            )
        }
        // Any already-produced physical unit stays with the producer after refund.
        log(game, contract, "환불")
    }
}
