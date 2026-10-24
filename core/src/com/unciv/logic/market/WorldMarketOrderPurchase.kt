package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType

object WorldMarketOrderPurchase {

    fun canBuy(buyer: Civilization, orderId: String): Boolean {
        val game = buyer.gameInfo
        val order = game.worldMarketOrders.firstOrNull {
            it.id == orderId
        } ?: return false

        val seller = game.civilizations.firstOrNull {
            it.civID == order.sellerCivId
        } ?: return false

        if (!buyer.isHuman() || !seller.isAI()) return false
        if (buyer == seller || buyer.isDefeated() || seller.isDefeated()) return false
        if (!buyer.knows(seller) || !seller.knows(buyer)) return false
        if (buyer.isAtWarWith(seller)) return false
        if (buyer.getDiplomacyManager(seller) == null) return false
        if (seller.getDiplomacyManager(buyer) == null) return false
        if (order.quantity <= 0 || order.unitPrice <= 0) return false
        if (buyer.gold < order.unitPrice) return false

        val resource = game.ruleset.tileResources[order.resourceName]
            ?: return false

        if (resource.isStockpiled != order.stockpiled) return false
        if (!order.stockpiled &&
            resource.resourceType != ResourceType.Luxury &&
            resource.resourceType != ResourceType.Strategic) return false

        val supply = if (order.stockpiled)
            seller.getStockpiledResourcesForTrade()
        else
            seller.getPerTurnResourcesWithOriginsForTrade()

        val available = supply.filter {
            it.origin == Constants.tradable &&
            it.resource.name == order.resourceName
        }.sumOf { it.amount }

        val otherOrders = game.worldMarketOrders.filter {
            it.id != order.id &&
            it.sellerCivId == seller.civID &&
            it.resourceName == order.resourceName
        }.sumOf { it.quantity.coerceAtLeast(0) }

        return available > otherOrders
    }

    fun buy(
        buyer: Civilization,
        orderId: String,
        hasReservationValidation: Boolean = false
    ): Boolean {
        if (!canBuy(buyer, orderId)) return false

        val game = buyer.gameInfo
        val order = game.worldMarketOrders.firstOrNull {
            it.id == orderId
        } ?: return false

        val seller = game.civilizations.firstOrNull {
            it.civID == order.sellerCivId
        } ?: return false

        val resource = game.ruleset.tileResources[order.resourceName]
            ?: return false

        val type = when {
            order.stockpiled -> TradeOfferType.Stockpiled_Resource
            resource.resourceType == ResourceType.Luxury ->
                TradeOfferType.Luxury_Resource
            resource.resourceType == ResourceType.Strategic ->
                TradeOfferType.Strategic_Resource
            else -> return false
        }

        val tradeLogic = TradeLogic(buyer, seller)

        tradeLogic.currentTrade.ourOffers.add(
            TradeOffer(
                Constants.flatGold,
                TradeOfferType.Gold,
                order.unitPrice,
                game.speed
            )
        )
        tradeLogic.currentTrade.theirOffers.add(
            TradeOffer(resource.name, type, 1, game.speed)
        )

        if (!TradeEvaluation().isTradeValid(
                tradeLogic.currentTrade, buyer, seller, order.id
            )) return false

        if (!canBuy(buyer, orderId)) return false

        tradeLogic.acceptTrade(applyGifts = false)

        order.quantity -= 1
        if (order.quantity <= 0)
            game.worldMarketOrders.remove(order)

        return true
    }
}
