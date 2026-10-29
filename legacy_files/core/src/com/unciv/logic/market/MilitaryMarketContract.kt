package com.unciv.logic.market

import com.unciv.logic.IsPartOfGameInfoSerialization

/** A prepaid one-unit procurement order produced in a seller city. */
data class MilitaryMarketContract(
    var id: String,
    var sellerCivId: String,
    var sellerCityId: String,
    var buyerCivId: String,
    var unitName: String,
    var price: Int,
    var orderedTurn: Int
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", "", "", "", 0, 0)
}

/** Recent military market contracts including deliveries, refunds and cancellations. */
data class MilitaryMarketHistoryEntry(
    var sellerCivId: String,
    var buyerCivId: String,
    var unitName: String,
    var price: Int,
    var turn: Int,
    var status: String
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", "", 0, 0, "")
}
