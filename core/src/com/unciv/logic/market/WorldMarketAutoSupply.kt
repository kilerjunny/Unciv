package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.tile.ResourceType

object WorldMarketAutoSupply {

    /** Update AI sell orders once during its normal turn. */
    fun update(seller: Civilization) {
        if (!seller.isAI() || seller.isDefeated()) return
        if (!seller.isMajorCiv() && !seller.isCityState) return

        val game = seller.gameInfo
        val supply = (
            seller.getPerTurnResourcesWithOriginsForTrade().toList() +
            seller.getStockpiledResourcesForTrade().toList()
        )

        val names = (
            supply.map { it.resource.name } +
            WorldMarketOrders.myOrders(seller).map { it.resourceName }
        ).distinct().sorted()

        for (name in names) {
            val resource = game.ruleset.tileResources[name]

            val amount = supply.filter {
                it.origin == Constants.tradable &&
                it.resource.name == name
            }.sumOf { it.amount }.coerceAtLeast(0)

            val reserve = when {
                resource == null -> Int.MAX_VALUE
                resource.resourceType == ResourceType.Bonus -> Int.MAX_VALUE
                resource.isStockpiled -> 10
                resource.resourceType == ResourceType.Luxury -> 1
                else -> 2
            }

            val target = if (reserve == Int.MAX_VALUE) 0
                else (amount - reserve).coerceIn(0, 3)

            val orders = WorldMarketOrders.myOrders(seller)
                .filter { it.resourceName == name }
                .sortedWith(
                    compareBy<MarketSellOrder> { it.createdTurn }
                        .thenBy { it.id }
                )

            val currentlyListed = orders.sumOf {
                it.quantity.coerceAtLeast(0)
            }

            if (currentlyListed > target) {
                var excess = currentlyListed - target
                for (order in orders.asReversed()) {
                    if (excess <= 0) break
                    val removed = minOf(excess, order.quantity)
                    order.quantity -= removed
                    excess -= removed
                    if (order.quantity <= 0)
                        game.worldMarketOrders.remove(order)
                }
            } else if (currentlyListed < target) {
                WorldMarketOrders.register(
                    seller, name, target - currentlyListed
                )
            }
        }
    }
}
