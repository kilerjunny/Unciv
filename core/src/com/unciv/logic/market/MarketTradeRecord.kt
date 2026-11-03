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
    var estimatedTransportCost: Int = -1, // GS_ECONOMIC_TRADE_ROUTES_V1: optional for older saves
    var originKind: String = "UNKNOWN", // GS_PRODUCTION_CITY_CAPITAL_V1
    // GS_TERRITORY_TOLLS_SUPPLY_V1: -1 means absent in old saves.
    var terrainCost: Int = -1,
    var mainSaving: Int = -1,
    var supplyCost: Int = -1,
    var tollCost: Int = -1,
    var portCost: Int = -1,
    // GS_WORLD_MARKET_CASH_TOLLS_V1: defaults keep old saves untaxed.
    var cashTollGold: Int = 0,
    var cashTollSettled: Boolean = false,
    var cashTollPayments: ArrayList<MarketTollPayment> = ArrayList()
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", "", 0, 0)
}
