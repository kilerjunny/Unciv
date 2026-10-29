package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.MilitaryMarket
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

/** The initial military procurement overview, kept off the main map render loop. */
class MilitaryMarketSection(
    private val playerView: CivView,
    private val refreshParent: () -> Unit
) : Table(BaseScreen.skin) {

    private var message = ""
    private var showHistory = false

    init {
        top().left()
        render()
    }

    private fun refresh() {
        render()
        refreshParent()
    }

    private fun render() {
        clearChildren()
        defaults().pad(5f)
        val buyer = playerView.getCiv()
        val game = buyer.gameInfo

        add("세계 군수 시장 · 시범 운영".toLabel(fontSize = Constants.headingFontSize)).left().growX().row()
        add("AI 생산국에 군사 유닛 1기 발주 · 실제 도시 생산 · 완공 시 인도".toLabel()).left().row()
        add("주문 즉시 금액 예치 / 인도 성공 시 생산국 지급 / 실패·취소 시 환불".toLabel()).left().row()
        add("내 보유 금: ${buyer.gold}G".toLabel()).left().row()
        if (message.isNotEmpty()) add(message.toLabel()).left().row()

        val historyButton = TextButton(if (showHistory) "군수 목록" else "군수 거래 내역", BaseScreen.skin)
        historyButton.onClick { showHistory = !showHistory; refresh() }
        add(historyButton).left().row()

        if (showHistory) {
            val records = game.militaryMarketHistory.asReversed().take(20)
            add("군수 거래 기록 (최근 ${records.size}건 표시, 최대 100건 보관)".toLabel()).left().row()
            if (records.isEmpty()) add("아직 군수 거래 기록이 없습니다.".toLabel()).left().row()
            for (record in records) {
                val seller = game.getCivilizationOrNull(record.sellerCivId)?.civName ?: "알 수 없음"
                val customer = game.getCivilizationOrNull(record.buyerCivId)?.civName ?: "알 수 없음"
                add("${record.turn}턴 · ${seller} → ${customer} · ${record.unitName} · ${record.price}G · ${record.status}".toLabel(hideIcons = true)).left().row()
            }
            return
        }

        add("진행 중인 군수 주문".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        val contracts = MilitaryMarket.myContracts(buyer)
        if (contracts.isEmpty()) add("현재 진행 중인 주문이 없습니다.".toLabel()).left().row()
        for (contract in contracts) {
            val seller = game.getCivilizationOrNull(contract.sellerCivId)?.civName ?: "알 수 없음"
            val line = Table(BaseScreen.skin)
            line.add("${contract.unitName} · ${seller} · ${contract.price}G · ${contract.orderedTurn}턴 발주".toLabel(hideIcons = true)).growX().left()
            val cancel = TextButton("주문 취소", BaseScreen.skin)
            cancel.isDisabled = !GUI.isAllowedChangeState()
            cancel.onClick {
                message = if (GUI.isAllowedChangeState() && MilitaryMarket.cancel(buyer, contract.id))
                    "군수 주문을 취소하고 예치금을 환불했습니다."
                else "취소할 수 없습니다."
                refresh()
            }
            line.add(cancel).padLeft(5f)
            add(line).growX().row()
        }

        add("생산 가능한 유닛 목록".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        if (!buyer.isHuman() || playerView.isSpectator()) {
            add("관전자 화면에서는 발주할 수 없습니다.".toLabel()).left().row()
            return
        }

        val offers = MilitaryMarket.offersFor(buyer)
        if (offers.isEmpty()) add("현재 주문 가능한 AI 생산국이 없습니다.".toLabel()).left().row()
        for (offer in offers) {
            val seller = game.getCivilizationOrNull(offer.sellerId) ?: continue
            val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: continue
            val line = Table(BaseScreen.skin)
            line.add("${offer.unitName} · ${seller.civName} (${city.name})".toLabel(hideIcons = true)).growX().left()
            line.add("${offer.price}G".toLabel()).padRight(6f)
            val orderButton = TextButton("발주", BaseScreen.skin)
            orderButton.isDisabled = !GUI.isAllowedChangeState() || buyer.gold < offer.price
            orderButton.onClick {
                message = if (GUI.isAllowedChangeState() && MilitaryMarket.order(buyer, offer))
                    "${offer.unitName} 1기 발주 완료 · 도시 생산 대기열 등록"
                else "발주 실패: 금액·도시 생산 가능 여부·외교 관계 확인"
                refresh()
            }
            line.add(orderButton).width(80f)
            add(line).growX().row()
        }
        if (offers.size == 100) add("표시 성능을 위해 최대 100개 상품을 보여줍니다.".toLabel()).left().row()
    }
}
