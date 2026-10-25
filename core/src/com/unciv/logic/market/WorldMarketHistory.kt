package com.unciv.logic.market
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

object WorldMarketHistory {
    fun record(seller: Civilization, buyer: Civilization, resource: String, qty: Int, gold: Int) {
        if (seller == buyer || qty <= 0 || gold <= 0) return
        val items = seller.gameInfo.worldMarketHistory
        items.add(MarketTradeRecord(seller.gameInfo.turns, seller.civID, buyer.civID, resource, qty, gold))
        if (items.size > 100) items.subList(0, items.size - 100).clear()
    }

    fun recent(game: GameInfo) =
        game.worldMarketHistory.takeLast(100).asReversed()
}
