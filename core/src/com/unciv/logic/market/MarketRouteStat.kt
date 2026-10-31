package com.unciv.logic.market

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.map.HexCoord

/** Compact traffic statistics for one route/turn (or lifetime route). */
data class MarketRouteStat(
    var turn: Int,
    var sellerCivId: String,
    var buyerCivId: String,
    var routeMode: String,
    var originCityName: String,
    var destinationCityName: String,
    var routeHash: Int,
    var routeTiles: ArrayList<HexCoord>,
    var tradeCount: Int,
    var quantity: Int,
    var totalGold: Int
) : IsPartOfGameInfoSerialization {
    constructor() : this(0, "", "", "", "", "", 0, ArrayList(), 0, 0, 0)
}
