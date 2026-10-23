package com.unciv.logic.market

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.models.ruleset.tile.ResourceType

data class MarketResourceListing(
    val seller: String,
    val resource: String,
    val quantity: Int,
    val stockpiled: Boolean
)

object WorldMarket {

    fun getResourceListings(gameInfo: GameInfo): List<MarketResourceListing> {
        val listings = ArrayList<MarketResourceListing>()

        for (civ in gameInfo.civilizations) {
            if ((!civ.isMajorCiv() && !civ.isCityState) || civ.isDefeated()) continue

            for (entry in civ.getPerTurnResourcesWithOriginsForTrade()) {
                if (entry.origin != Constants.tradable) continue
                if (entry.amount <= 0) continue
                if (entry.resource.resourceType == ResourceType.Bonus) continue

                listings.add(
                    MarketResourceListing(
                        civ.civName,
                        entry.resource.name,
                        entry.amount,
                        false
                    )
                )
            }

            for (entry in civ.getStockpiledResourcesForTrade()) {
                if (entry.origin != Constants.tradable) continue
                if (entry.amount <= 0) continue

                listings.add(
                    MarketResourceListing(
                        civ.civName,
                        entry.resource.name,
                        entry.amount,
                        true
                    )
                )
            }
        }

        return listings.sortedWith(
            compareBy({ it.resource }, { it.seller })
        )
    }
}
