package com.unciv.logic.market

import com.unciv.logic.IsPartOfGameInfoSerialization

data class MarketSellOrder(
    var id: String,
    var sellerCivId: String,
    var resourceName: String,
    var quantity: Int,
    var unitPrice: Int,
    var stockpiled: Boolean,
    var createdTurn: Int
) : IsPartOfGameInfoSerialization {

    constructor() : this("", "", "", 0, 0, false, 0)
}
