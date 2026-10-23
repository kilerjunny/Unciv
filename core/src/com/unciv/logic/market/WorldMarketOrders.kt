package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import java.util.UUID

object WorldMarketOrders {

    fun availableToList(
        seller: Civilization,
        resourceName: String
    ): Int {
        val resource = seller.gameInfo.ruleset.tileResources[resourceName]
            ?: return 0

        if (resource.resourceType == ResourceType.Bonus) return 0

        val supply = if (resource.isStockpiled)
            seller.getStockpiledResourcesForTrade()
        else
            seller.getPerTurnResourcesWithOriginsForTrade()

        val available = supply
            .filter {
                it.origin == Constants.tradable &&
                it.resource.name == resourceName
            }
            .sumOf { it.amount }

        val alreadyListed = seller.gameInfo.worldMarketOrders
            .filter {
                it.sellerCivId == seller.civID &&
                it.resourceName == resourceName
            }
            .sumOf { it.quantity.coerceAtLeast(0) }

        return (available - alreadyListed).coerceAtLeast(0)
    }

    fun register(
        seller: Civilization,
        resourceName: String,
        quantity: Int
    ): MarketSellOrder? {
        if ((!seller.isMajorCiv() && !seller.isCityState)
            || seller.isDefeated()) return null

        if (quantity <= 0 ||
            quantity > availableToList(seller, resourceName)) return null

        val resource = seller.gameInfo.ruleset.tileResources[resourceName]
            ?: return null

        val listing = MarketResourceListing(
            seller.civName,
            resourceName,
            quantity,
            resource.isStockpiled
        )

        val price = WorldMarketTrading.unitPrice(listing, seller)
        if (price <= 0) return null

        val order = MarketSellOrder(
            UUID.randomUUID().toString(),
            seller.civID,
            resourceName,
            quantity,
            price,
            resource.isStockpiled,
            seller.gameInfo.turns
        )

        seller.gameInfo.worldMarketOrders.add(order)
        return order
    }

    fun cancel(
        seller: Civilization,
        orderId: String
    ): Boolean {
        val order = seller.gameInfo.worldMarketOrders.firstOrNull {
            it.id == orderId && it.sellerCivId == seller.civID
        } ?: return false

        return seller.gameInfo.worldMarketOrders.remove(order)
    }

    fun myOrders(seller: Civilization): List<MarketSellOrder> =
        seller.gameInfo.worldMarketOrders.filter {
            it.sellerCivId == seller.civID
        }
}
