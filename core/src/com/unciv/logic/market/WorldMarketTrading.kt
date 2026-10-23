package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.tile.ResourceType

object WorldMarketTrading {

    fun unitPrice(listing: MarketResourceListing, buyer: Civilization): Int {
        val resource = buyer.gameInfo.ruleset.tileResources[listing.resource]
            ?: return 0
        return when {
            listing.stockpiled -> 25
            resource.resourceType == ResourceType.Luxury -> 300
            resource.resourceType == ResourceType.Strategic -> 50
            else -> 0
        }
    }

    fun canBuy(
        buyer: Civilization,
        seller: Civilization,
        listing: MarketResourceListing
    ): Boolean {
        if (!buyer.isHuman() || !seller.isAI()) return false
        if (buyer == seller || buyer.isDefeated() || seller.isDefeated()) return false
        if (!buyer.knows(seller) || buyer.isAtWarWith(seller)) return false
        if (buyer.getDiplomacyManager(seller) == null) return false
        if (seller.getDiplomacyManager(buyer) == null) return false

        val price = unitPrice(listing, buyer)
        if (price <= 0 || buyer.gold < price) return false

        val available = if (listing.stockpiled)
            seller.getStockpiledResourcesForTrade()
        else
            seller.getPerTurnResourcesWithOriginsForTrade()

        return available.any {
            it.origin == Constants.tradable &&
            it.resource.name == listing.resource &&
            it.amount > 0
        }
    }

    fun buyOne(
        buyer: Civilization,
        seller: Civilization,
        listing: MarketResourceListing
    ): Boolean {
        if (!canBuy(buyer, seller, listing)) return false

        val resource = buyer.gameInfo.ruleset.tileResources[listing.resource]
            ?: return false

        val offerType = when {
            listing.stockpiled -> TradeOfferType.Stockpiled_Resource
            resource.resourceType == ResourceType.Luxury ->
                TradeOfferType.Luxury_Resource
            resource.resourceType == ResourceType.Strategic ->
                TradeOfferType.Strategic_Resource
            else -> return false
        }

        val price = unitPrice(listing, buyer)
        val trade = TradeLogic(buyer, seller)

        trade.currentTrade.ourOffers.add(
            TradeOffer(Constants.flatGold, TradeOfferType.Gold,
                price, buyer.gameInfo.speed)
        )
        trade.currentTrade.theirOffers.add(
            TradeOffer(resource.name, offerType,
                1, buyer.gameInfo.speed)
        )

        if (!TradeEvaluation().isTradeValid(
                trade.currentTrade, buyer, seller
            )) return false

        trade.acceptTrade(applyGifts = false)
        return true
    }
}
