package com.unciv.logic.market

import com.unciv.logic.IsPartOfGameInfoSerialization

/** A transfer of actual gold to a third-party country for a completed market trade. */
data class MarketTollPayment(
    var civId: String = "",
    var gold: Int = 0,
    // GS_MARITIME_PASSAGE_TRANSSHIPMENT_V2: optional, old records stay readable.
    var landGold: Int = 0,
    var seaGold: Int = 0,
    var harborGold: Int = 0
) : IsPartOfGameInfoSerialization
