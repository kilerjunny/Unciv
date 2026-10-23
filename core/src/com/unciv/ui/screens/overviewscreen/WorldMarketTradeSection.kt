package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.WorldMarket
import com.unciv.logic.market.WorldMarketTrading
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

class WorldMarketTradeSection(
    private val playerView: CivView,
    private val parentTab: TradesOverviewTab
) : Table(BaseScreen.skin) {

    private var expanded = true
    private var message = ""

    init {
        top().left()
        render()
    }

    private fun render() {
        clearChildren()
        defaults().pad(5f)

        val header = Table(BaseScreen.skin)
        header.add("세계 시장".toLabel(
            fontSize = Constants.headingFontSize
        )).left().growX()

        val toggle = TextButton(
            if (expanded) "목록 접기" else "목록 보기",
            BaseScreen.skin
        )
        toggle.onClick {
            expanded = !expanded
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        header.add(toggle)

        add(header).growX().row()

        if (message.isNotEmpty())
            add(message.toLabel()).left().growX().row()

        if (!expanded) return

        add("상품 1개 단위 구매 / 가격은 임시 기준가".toLabel())
            .left().growX().row()

        val buyer = playerView.getCiv()
        val gameInfo = buyer.gameInfo
        val spectator = playerView.isSpectator()

        val known = buyer.getKnownCivs()
            .map { it.civName }.toSet()

        val listings = WorldMarket.getResourceListings(gameInfo)
            .filter {
                it.seller != buyer.civName &&
                (spectator || it.seller in known)
            }

        if (listings.isEmpty()) {
            add("현재 거래 가능한 상품이 없습니다.".toLabel())
                .left().growX().row()
        }

        for (listing in listings) {
            val seller = gameInfo.civilizations.firstOrNull {
                it.civName == listing.seller
            } ?: continue

            val price = WorldMarketTrading.unitPrice(listing, buyer)
            val line = Table(BaseScreen.skin)

            line.add(
                "${listing.resource} · ${listing.seller} · ${listing.quantity}개"
                    .toLabel(hideIcons = true)
            ).left().growX()

            line.add("${price}G".toLabel()).padRight(6f)

            if (!spectator) {
                val button = TextButton("구매", BaseScreen.skin)
                button.isDisabled =
                    !GUI.isAllowedChangeState() ||
                    !WorldMarketTrading.canBuy(buyer, seller, listing)

                button.onClick {
                    message = if (
                        GUI.isAllowedChangeState() &&
                        WorldMarketTrading.buyOne(buyer, seller, listing)
                    ) {
                        "${listing.resource} 1개 구매 완료"
                    } else {
                        "구매 불가: 금액·재고·외교 관계 확인"
                    }
                    render()
                    parentTab.overviewScreen.resizePage(parentTab)
                }
                line.add(button).width(80f)
            }

            add(line).growX().row()
        }
    }
}
