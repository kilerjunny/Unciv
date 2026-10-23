package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.market.WorldMarket
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

class WorldMarketOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    init {
        top().left()
        defaults().pad(6f)

        add(
            "세계 자원 시장".toLabel(
                fontSize = Constants.headingFontSize
            )
        ).left().row()

        add("판매 가능 자원 목록 / 조회 전용".toLabel())
            .left().row()

        val player = viewingPlayer.getCiv()
        val knownSellers = if (viewingPlayer.isSpectator()) {
            null
        } else {
            (player.getKnownCivs().map { it.civName } +
                player.civName).toSet()
        }

        val listings = WorldMarket.getResourceListings(gameInfo)
            .filter {
                knownSellers == null || it.seller in knownSellers
            }

        if (listings.isEmpty()) {
            add("현재 확인 가능한 상품이 없습니다.".toLabel())
                .left().row()
        }

        for (listing in listings) {
            val line = Table(BaseScreen.skin)

            line.add(listing.resource.toLabel(hideIcons = true))
                .growX().left()

            line.add(listing.seller.toLabel(hideIcons = true))
                .growX().left()

            line.add(listing.quantity.toString().toLabel())
                .right().padRight(8f)

            line.add(
                (if (listing.stockpiled) "비축" else "턴 공급")
                    .toLabel()
            ).right()

            add(line).growX().row()
        }
    }
}
