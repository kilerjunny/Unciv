package com.unciv.logic.market

import com.unciv.logic.IsPartOfGameInfoSerialization

/** A player seller's public build-to-order listing; no unit exists until ordered. */
data class MilitaryMarketListing(
    var id: String,
    var sellerCivId: String,
    var sellerCityId: String,
    var unitName: String,
    var price: Int,
    var createdTurn: Int
) : IsPartOfGameInfoSerialization {
    constructor() : this("", "", "", "", 0, 0)
}
