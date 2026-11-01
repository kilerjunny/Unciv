package com.unciv.logic.market
import com.unciv.logic.IsPartOfGameInfoSerialization

data class MarketTradeRecord(
    var turn: Int, var sellerCivId: String, var buyerCivId: String,
    var resourceName: String, var quantity: Int, var totalGold: Int,
    // Optional for old saves: observational route metadata, not delayed delivery.
    var routeMode: String = "UNKNOWN",
    var originCityName: String = "",
    var destinationCityName: String = "",
    var routeTiles: ArrayList<com.unciv.logic.map.HexCoord> = ArrayList(),
    var estimatedTransportCost: Int = -1 // GS_ECONOMIC_TRADE_ROUTES_V1: optional for older saves
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", "", 0, 0)
}
