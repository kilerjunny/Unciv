package com.unciv.ui.screens.overviewscreen

import com.unciv.view.CivView

/** GS_MERCHANT_OVERVIEW_TAB_V1
 * Merchant intelligence tab. The underlying market transactions remain in Trades.
 * This tab reports observed routes/fees; autonomous merchant AI is not implemented yet.
 */
class MerchantOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {
    init {
        top().left()
        defaults().pad(6f)
        add(WorldMarketTradeSection(viewingPlayer, this, merchantMode = true)).growX().row()
    }
}
