package com.unciv.logic.market
import com.unciv.logic.IsPartOfGameInfoSerialization

data class MarketTradeRecord(
    var turn: Int, var sellerCivId: String, var buyerCivId: String,
    var resourceName: String, var quantity: Int, var totalGold: Int,
    // Optional for old saves: observational route metadata, not delayed delivery.
    var routeMode: String = "UNKNOWN",
    var originCityName: String = "",
    var destinationCityName: String = "",
    var routeTiles: ArrayList<com.unciv.logic.map.HexCoord> = ArrayList()
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", "", 0, 0)
}
