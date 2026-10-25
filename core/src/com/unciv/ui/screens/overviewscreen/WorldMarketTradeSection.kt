package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.WorldMarket
import com.unciv.logic.market.WorldMarketTrading
import com.unciv.logic.market.WorldMarketOrderPurchase
import com.unciv.logic.market.WorldMarketHistory
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

class WorldMarketTradeSection(
    private val playerView: CivView,
    private val parentTab: TradesOverviewTab
) : Table(BaseScreen.skin) {

    private var expanded = true
    private var historyOpen = false
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

        val resourcesButton =
            TextButton("자원 현황", BaseScreen.skin)
        resourcesButton.onClick {
            parentTab.overviewScreen.select(
                EmpireOverviewCategories.Resources, ""
            )
        }
        header.add(resourcesButton).padLeft(5f)

        add(header).growX().row()

        if (message.isNotEmpty())
            add(message.toLabel()).left().growX().row()

        if (!expanded) return

        val historyButton = TextButton(if (historyOpen) "시장 보기" else "거래 내역", BaseScreen.skin)
        historyButton.onClick {
            historyOpen = !historyOpen
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        add(historyButton).left().row()

        if (historyOpen) {
            val game = playerView.getCiv().gameInfo
            val records = WorldMarketHistory.recent(game)
            add("최근 세계 시장 거래 ${records.size}건 (최대 100건)".toLabel()).left().row()
            if (records.isEmpty())
                add("체결된 거래가 없습니다.".toLabel()).left().row()

            for (it in records) {
                val seller = game.getCivilizationOrNull(it.sellerCivId)?.civName ?: "알 수 없음"
                val buyer = game.getCivilizationOrNull(it.buyerCivId)?.civName ?: "알 수 없음"
                add("턴 ${it.turn}: ${seller} → ${buyer}".toLabel(hideIcons = true)).left().row()
                add("${it.resourceName} ${it.quantity}개 · ${it.totalGold}G".toLabel(hideIcons = true)).left().row()
            }
            return
        }

        add("상품 1개 단위 구매 / 가격은 임시 기준가".toLabel())
            .left().growX().row()

        val buyer = playerView.getCiv()
        add("내 보유 금: ${buyer.gold}G".toLabel())
            .left().growX().row()
        val gameInfo = buyer.gameInfo
        val spectator = playerView.isSpectator()

        if (!spectator) {
            add(
                WorldMarketSellSection(playerView) {
                    parentTab.overviewScreen.resizePage(parentTab)
                }
            ).growX().row()
        }

        val known = buyer.getKnownCivs()
            .map { it.civName }.toSet()


        val orders = gameInfo.worldMarketOrders
            .filter { order ->
                if (order.quantity <= 0 || order.unitPrice <= 0)
                    return@filter false

                val seller = gameInfo.civilizations.firstOrNull {
                    it.civID == order.sellerCivId
                } ?: return@filter false

                seller != buyer &&
                    seller.isAI() &&
                    !seller.isDefeated() &&
                    (spectator || seller.civName in known)
            }
            .sortedWith(
                compareBy(
                    { it.resourceName },
                    { it.unitPrice },
                    { it.sellerCivId },
                    { it.id }
                )
            )

        add("시장 등록 상품".toLabel(
            fontSize = Constants.headingFontSize
        )).left().growX().row()

        if (orders.isEmpty()) {
            add("현재 등록된 판매 상품이 없습니다.".toLabel())
                .left().growX().row()
        }

        for (order in orders) {
            val seller = gameInfo.civilizations.firstOrNull {
                it.civID == order.sellerCivId
            } ?: continue

            val line = Table(BaseScreen.skin)

            line.add(
                ImageGetter.getResourcePortrait(
                    order.resourceName, 32f
                )
            ).size(32f).padRight(5f)

            val owned = buyer.getResourceAmount(order.resourceName)
            val info = Table(BaseScreen.skin)

            info.add(
                "${order.resourceName} · ${seller.civName}"
                    .toLabel(hideIcons = true)
            ).left().row()

            info.add(
                "판매 ${order.quantity}개 · 내 가용 ${owned}개"
                    .toLabel()
            ).left().row()

            line.add(info).growX().left()

            line.add("${order.unitPrice}G".toLabel())
                .padRight(6f)

            if (!spectator) {
                val button = TextButton("구매", BaseScreen.skin)
                button.isDisabled =
                    !GUI.isAllowedChangeState() ||
                    !WorldMarketOrderPurchase.canBuy(buyer, order.id)

                button.onClick {
                    message = if (
                        GUI.isAllowedChangeState() &&
                        WorldMarketOrderPurchase.buy(buyer, order.id)
                    ) {
                        "${order.resourceName} 1개 구매 완료"
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
