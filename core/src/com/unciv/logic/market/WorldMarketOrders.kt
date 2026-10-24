package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType
import java.util.UUID
import yairm210.purity.annotations.Readonly

object WorldMarketOrders {

    /** Adjust outstanding sell orders to the seller's current supply. */
    fun reconcileSellerOrders(seller: Civilization) {
        val game = seller.gameInfo

        val orders = game.worldMarketOrders
            .filter { it.sellerCivId == seller.civID }
            .sortedWith(
                compareBy<MarketSellOrder> { it.createdTurn }
                    .thenBy { it.id }
            )

        val allocated = HashMap<String, Int>()

        for (order in orders) {
            val resource = game.ruleset.tileResources[order.resourceName]

            val valid = resource != null &&
                resource.resourceType != ResourceType.Bonus &&
                resource.isStockpiled == order.stockpiled &&
                !seller.isDefeated()

            val supply = if (!valid) {
                0
            } else {
                val resources = if (resource!!.isStockpiled)
                    seller.getStockpiledResourcesForTrade()
                else
                    seller.getPerTurnResourcesWithOriginsForTrade()

                resources.filter {
                    it.origin == Constants.tradable &&
                    it.resource.name == order.resourceName
                }.sumOf { it.amount }.coerceAtLeast(0)
            }

            val alreadyAllocated =
                allocated[order.resourceName] ?: 0

            val remaining =
                (supply - alreadyAllocated).coerceAtLeast(0)

            order.quantity =
                order.quantity.coerceIn(0, remaining)

            if (order.quantity == 0) {
                game.worldMarketOrders.remove(order)
            } else {
                allocated[order.resourceName] =
                    alreadyAllocated + order.quantity
            }
        }
    }

    @Readonly
    fun reservedFor(
        seller: Civilization,
        resourceName: String,
        fulfillingOrderId: String? = null
    ): Int {
        val orders = seller.gameInfo.worldMarketOrders.filter {
            it.sellerCivId == seller.civID &&
            it.resourceName == resourceName &&
            it.quantity > 0
        }

        val reserved = orders.sumOf { it.quantity }

        // An order being fulfilled may use one reserved unit.
        val fulfilling = orders.any { it.id == fulfillingOrderId }
        return (reserved - if (fulfilling) 1 else 0)
            .coerceAtLeast(0)
    }

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

        val alreadyListed = reservedFor(seller, resourceName)

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
