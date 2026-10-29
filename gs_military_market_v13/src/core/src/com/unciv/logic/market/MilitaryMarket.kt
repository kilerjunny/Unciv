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

/** Build-to-order unit trading: seller produces, seller releases, buyer takes the same unit. */
object MilitaryMarket {
    const val PRODUCING = "PRODUCING"
    const val AWAITING_DISPATCH = "AWAITING_DISPATCH"
    const val AWAITING_RECEIPT = "AWAITING_RECEIPT"

    data class Offer(val sellerId: String, val cityId: String, val unitName: String, val price: Int)

    /** AI must not move or attack units reserved for a paid contract. */
    @Readonly
    fun isReservedUnit(unit: MapUnit): Boolean =
        unit.civ.gameInfo.militaryMarketContracts.any {
            it.sellerCivId == unit.civ.civID &&
                it.status != PRODUCING && it.producedUnitId == unit.id
        }

    private fun tradingPartners(buyer: Civilization, seller: Civilization): Boolean =
        buyer != seller && (buyer.isHuman() || buyer.isAI()) &&
            (seller.isHuman() || seller.isAI()) &&
            buyer.isMajorCiv() && seller.isMajorCiv() &&
            !buyer.isDefeated() && !seller.isDefeated() &&
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

    fun referencePrice(seller: Civilization, city: City, unit: BaseUnit): Int =
        (unit.getProductionCost(seller, city).toLong() * 3L)
            .coerceIn(60L, 100000L).toInt()

    fun myListings(civ: Civilization): List<MilitaryMarketListing> =
        civ.gameInfo.militaryMarketListings.filter { it.sellerCivId == civ.civID }

    /** One offer per city, fixed server-side price; no prebuilt units or inventory needed. */
    fun listForSale(seller: Civilization, cityId: String, unitName: String): Boolean {
        if (!seller.isHuman() || seller.isDefeated() || !seller.isMajorCiv()) return false
        val game = seller.gameInfo
        val city = seller.cities.firstOrNull { it.id == cityId } ?: return false
        val unit = game.ruleset.units[unitName] ?: return false
        if (!eligibleCity(city) || !unit.isMilitary || unit.isGreatPerson ||
            !city.cityConstructions.canAddToQueue(unit) ||
            city.cityConstructions.getConstructableUnits().none { it.name == unitName } ||
            game.militaryMarketListings.any { it.sellerCityId == cityId && it.sellerCivId == seller.civID }
        ) return false

        game.militaryMarketListings.add(MilitaryMarketListing(
            UUID.randomUUID().toString(), seller.civID, city.id, unit.name,
            referencePrice(seller, city, unit), game.turns
        ))
        return true
    }

    fun withdrawListing(seller: Civilization, listingId: String): Boolean {
        if (!seller.isHuman()) return false
        val listing = seller.gameInfo.militaryMarketListings.firstOrNull {
            it.id == listingId && it.sellerCivId == seller.civID
        } ?: return false
        return seller.gameInfo.militaryMarketListings.remove(listing)
    }

    /** AI sellers display their buildable units automatically, player sellers only their listings. */
    fun offersFor(buyer: Civilization, limit: Int = 100): List<Offer> {
        if (buyer.isDefeated() || buyer.cities.isEmpty() || !buyer.isMajorCiv()) return emptyList()
        val game = buyer.gameInfo
        val results = ArrayList<Offer>()

        // Prioritize player-created listings so they are visible even in large AI worlds.
        for (listing in game.militaryMarketListings) {
            val seller = game.getCivilizationOrNull(listing.sellerCivId) ?: continue
            if (!seller.isHuman() || !tradingPartners(buyer, seller)) continue
            val city = seller.cities.firstOrNull { it.id == listing.sellerCityId } ?: continue
            val unit = game.ruleset.units[listing.unitName] ?: continue
            if (!eligibleCity(city) || !unit.isMilitary || unit.isGreatPerson ||
                !city.cityConstructions.canAddToQueue(unit)) continue
            results.add(Offer(seller.civID, city.id, unit.name, listing.price))
            if (results.size >= limit) return results
        }

        // AI sellers don't need serialized offer objects, preventing save-file bloat.
        for (seller in game.civilizations) {
            if (!seller.isAI() || !tradingPartners(buyer, seller)) continue
            val city = seller.cities.asSequence()
                .filter { eligibleCity(it) }
                .maxByOrNull { it.cityStats.currentCityStats.production } ?: continue
            for (unit in city.cityConstructions.getConstructableUnits()) {
                if (!unit.isMilitary || unit.isGreatPerson) continue
                results.add(Offer(seller.civID, city.id, unit.name,
                    referencePrice(seller, city, unit)))
                if (results.size >= limit) return results
            }
        }
        return results
    }

    /** Recheck every offer at the moment of buying, never trust UI or cached prices. */
    fun order(buyer: Civilization, offer: Offer): Boolean {
        val game = buyer.gameInfo
        val seller = game.getCivilizationOrNull(offer.sellerId) ?: return false
        if (!tradingPartners(buyer, seller)) return false
        val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: return false
        if (!eligibleCity(city)) return false
        val unit = game.ruleset.units[offer.unitName] ?: return false
        if (!unit.isMilitary || unit.isGreatPerson ||
            !city.cityConstructions.canAddToQueue(unit) ||
            city.cityConstructions.getConstructableUnits().none { it.name == unit.name } ||
            // A pre-existing queue entry with the same name makes attribution ambiguous.
            city.cityConstructions.constructionQueue.contains(unit.name)
        ) return false

        val listing = if (seller.isHuman()) game.militaryMarketListings.firstOrNull {
            it.sellerCivId == seller.civID && it.sellerCityId == city.id &&
                it.unitName == unit.name
        } ?: return false else null
        val price = listing?.price ?: referencePrice(seller, city, unit)
        if (price <= 0 || buyer.gold < price || game.militaryMarketContracts.size >= 40) return false

        val before = city.cityConstructions.constructionQueue.count { it == unit.name }
        city.cityConstructions.addToQueue(unit, addToTop = true)
        if (city.cityConstructions.constructionQueue.count { it == unit.name } != before + 1) return false

        buyer.addGold(-price)
        game.militaryMarketContracts.add(MilitaryMarketContract(
            UUID.randomUUID().toString(), seller.civID, city.id,
            buyer.civID, unit.name, price, game.turns
        ))
        // A city cannot service several simultaneous custom-production orders.
        game.militaryMarketListings.removeAll {
            it.sellerCivId == seller.civID && it.sellerCityId == city.id
        }
        if (buyer.isHuman()) buyer.addNotification(
            "군수 시장: [${seller.civName}]에 [${unit.name}] 발주 (-${price}G 예치)",
            NotificationCategory.Trade, NotificationIcon.Trade, seller.civName
        )
        if (seller.isHuman()) seller.addNotification(
            "군수 시장: [${buyer.civName}]의 [${unit.name}] 주문이 접수되었습니다.",
            NotificationCategory.Trade, NotificationIcon.Trade, buyer.civName
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

    fun onProduced(city: City, producedUnit: MapUnit) {
        val game = city.civ.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.status == PRODUCING && it.sellerCivId == city.civ.civID &&
                it.sellerCityId == city.id && it.unitName == producedUnit.baseUnit.name
        } ?: return
        contract.producedUnitId = producedUnit.id
        contract.status = AWAITING_DISPATCH
        game.getCivilizationOrNull(contract.buyerCivId)?.takeIf { it.isHuman() }?.addNotification(
            "군수 시장: [${contract.unitName}] 생산 완료. 판매국 인도 승인을 기다립니다.",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        if (city.civ.isHuman()) city.civ.addNotification(
            "군수 시장: [${contract.unitName}] 생산 완료. 거래 탭에서 인도하기를 선택하세요.",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        else dispatch(city.civ, contract.id)
    }

    /** Seller accepts handover first; retains the unit until buyer takes it. */
    fun dispatch(seller: Civilization, contractId: String): Boolean {
        val game = seller.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.id == contractId && it.sellerCivId == seller.civID &&
                it.status == AWAITING_DISPATCH
        } ?: return false
        if (seller.isDefeated() || producedUnit(game, contract) == null) return false
        contract.status = AWAITING_RECEIPT
        game.getCivilizationOrNull(contract.buyerCivId)?.takeIf { it.isHuman() }?.addNotification(
            "군수 시장: [${seller.civName}]이 [${contract.unitName}] 인도를 승인했습니다. 인도받기를 선택하세요.",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        return true
    }

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
        if (owner != null && owner != buyer &&
            !buyer.diplomacyFunctions.canPassThroughTiles(owner)) return false
        if (unit.baseUnit.isWaterUnit && tile.isLand && !tile.isCityCenter()) return false
        if (unit.baseUnit.isLandUnit && tile.isWater && !buyer.tech.unitsCanEmbark) return false
        return true
    }

    fun receive(buyer: Civilization, contractId: String): Boolean {
        val game = buyer.gameInfo
        val contract = game.militaryMarketContracts.firstOrNull {
            it.id == contractId && it.buyerCivId == buyer.civID
        } ?: return false
        if (!canReceive(buyer, contract)) return false
        val seller = game.getCivilizationOrNull(contract.sellerCivId) ?: return false
        val unit = producedUnit(game, contract) ?: return false
        val origin = unit.getTile()
        unit.gift(buyer) // Transfer SAME unit without spawning or teleporting.
        unit.currentMovement = 0f
        seller.addGold(contract.price)
        game.militaryMarketContracts.remove(contract)
        log(game, contract, "인도 완료")
        if (buyer.isHuman()) buyer.addNotification(
            "군수 시장: [${contract.unitName}] 인도 완료. 생산기지에서 출발하세요.",
            origin.position, NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        if (seller.isHuman()) seller.addNotification(
            "군수 시장: [${contract.unitName}] 인도 완료 (+${contract.price}G)",
            NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
        )
        return true
    }

    /** Reconcile once per game turn; auto-dismiss invalid deals and stale public listings. */
    fun reconcile(game: GameInfo) {
        game.militaryMarketListings.removeAll { listing ->
            val seller = game.getCivilizationOrNull(listing.sellerCivId)
            val city = seller?.cities?.firstOrNull { it.id == listing.sellerCityId }
            val unit = game.ruleset.units[listing.unitName]
            seller == null || !seller.isHuman() || seller.isDefeated() || city == null ||
                unit == null || !unit.isMilitary || !eligibleCity(city) ||
                !city.cityConstructions.canAddToQueue(unit)
        }
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

    /** Unbuilt orders are cancellable by the buyer with a full refund. */
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
            if (buyer.isHuman()) buyer.addNotification(
                "군수 시장: [${contract.unitName}] 주문이 ${reason}되었습니다. (+${contract.price}G 환불)",
                NotificationCategory.Trade, NotificationIcon.Trade, contract.unitName
            )
        }
        // A completed unit not received remains the seller's own unit.
        log(game, contract, "환불")
    }
}
