package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType

object WorldMarketAI {

    /** AI buys at most one unit from a player's registered orders per turn. */
    fun buyFromPlayerOrders(buyer: Civilization) {
        if (!buyer.isAI() || buyer.isDefeated()) return
        if (!buyer.isMajorCiv() && !buyer.isCityState) return

        val game = buyer.gameInfo
        val evaluation = TradeEvaluation()

        val orders = game.worldMarketOrders
            .filter { it.quantity > 0 && it.unitPrice > 0 }
            .sortedWith(
                compareBy<MarketSellOrder> { it.unitPrice }
                    .thenBy { it.createdTurn }
                    .thenBy { it.id }
            )

        for (order in orders) {
            val seller = game.civilizations.firstOrNull {
                it.civID == order.sellerCivId
            } ?: continue

            if (!seller.isHuman() || seller.isDefeated()) continue
            if (!buyer.knows(seller) || !seller.knows(buyer)) continue
            if (buyer.isAtWarWith(seller)) continue
            if (buyer.getDiplomacyManager(seller) == null) continue
            if (seller.getDiplomacyManager(buyer) == null) continue

            val resource = game.ruleset.tileResources[order.resourceName]
                ?: continue

            if (resource.isStockpiled != order.stockpiled) continue

            val offerType = when {
                order.stockpiled ->
                    TradeOfferType.Stockpiled_Resource
                resource.resourceType == ResourceType.Luxury ->
                    TradeOfferType.Luxury_Resource
                resource.resourceType == ResourceType.Strategic ->
                    TradeOfferType.Strategic_Resource
                else -> continue
            }

            // Keep at least half the treasury for other AI expenses.
            if (buyer.gold.toLong() < order.unitPrice.toLong() * 2L)
                continue

            // Don't buy luxuries already owned or excess strategic resources.
            if (resource.resourceType == ResourceType.Luxury &&
                buyer.hasResource(resource.name)) continue

            if (!order.stockpiled &&
                resource.resourceType == ResourceType.Strategic &&
                buyer.getResourceAmount(resource.name) >= 2) continue

            val available = if (order.stockpiled)
                seller.getStockpiledResourcesForTrade()
            else
                seller.getPerTurnResourcesWithOriginsForTrade()

            if (available.none {
                it.origin == Constants.tradable &&
                it.resource.name == order.resourceName &&
                it.amount > 0
            }) continue

            val tradeLogic = TradeLogic(buyer, seller)
            val trade = tradeLogic.currentTrade

            val resourceOffer = TradeOffer(
                resource.name, offerType, 1, game.speed
            )

            val value = evaluation.evaluateBuyCostWithInflation(
                resourceOffer, buyer, seller, trade
            )

            if (value < order.unitPrice) continue

            trade.ourOffers.add(
                TradeOffer(
                    Constants.flatGold,
                    TradeOfferType.Gold,
                    order.unitPrice,
                    game.speed
                )
            )

            trade.theirOffers.add(resourceOffer)

            if (!evaluation.isTradeValid(trade, buyer, seller))
                continue

            // The existing trade system handles gold, resource
            // contracts, and resource cache updates.
            tradeLogic.acceptTrade(applyGifts = false)

            order.quantity -= 1
            if (order.quantity <= 0) {
                game.worldMarketOrders.remove(order)
            }

            seller.addNotification(
                "세계 시장: [${buyer.civName}]이 [${resource.name}] 1개를 구매했습니다. (+${order.unitPrice}G)",
                NotificationCategory.Trade,
                NotificationIcon.Trade,
                buyer.civName
            )

            // Limit each AI civilization to one purchase per turn.
            return
        }
    }
}
