package com.unciv.logic.market
import com.unciv.logic.IsPartOfGameInfoSerialization

data class MarketTradeRecord(
    var turn: Int, var sellerCivId: String, var buyerCivId: String,
    var resourceName: String, var quantity: Int, var totalGold: Int
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", "", 0, 0)
}
