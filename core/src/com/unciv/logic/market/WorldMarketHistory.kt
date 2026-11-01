package com.unciv.logic.market
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

object WorldMarketHistory {
    fun record(seller: Civilization, buyer: Civilization, resource: String, qty: Int, gold: Int) {
        if (seller == buyer || qty <= 0 || gold <= 0) return
        // GS_TRADE_MAP_SPEED_V1: recover any remaining old history once per save.
        WorldMarketRouteStats.initializeIfNeeded(seller.gameInfo)
        val items = seller.gameInfo.worldMarketHistory
        // The original trade has already settled; route recognition is best-effort only.
        // If the planner cannot recognize the path, keep the transaction record intact.
        val item = MarketTradeRecord(seller.gameInfo.turns, seller.civID, buyer.civID, resource, qty, gold)
        try {
            val route = WorldMarketRoutePlanner.plan(seller, buyer, resource) // GS_PRODUCTION_CITY_CAPITAL_V1
            item.routeMode = route.mode
            item.originCityName = route.originCityName
            item.destinationCityName = route.destinationCityName
            item.routeTiles = ArrayList(route.tiles)
            item.estimatedTransportCost = route.estimatedTransportCost // GS_ECONOMIC_TRADE_ROUTES_V1
            item.originKind = route.originKind // GS_PRODUCTION_CITY_CAPITAL_V1
        } catch (_: Exception) {
            item.routeMode = "UNRESOLVED"
        }
        items.add(item)
        WorldMarketRouteStats.record(seller.gameInfo, item)
        if (items.size > 100) items.subList(0, items.size - 100).clear()
    }

    fun recent(game: GameInfo) =
        game.worldMarketHistory.takeLast(100).asReversed()
}
