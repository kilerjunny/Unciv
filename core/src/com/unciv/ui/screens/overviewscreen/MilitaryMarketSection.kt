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

/** Market view. Production takes turns; dispatch and receipt are independent actions. */
class MilitaryMarketSection(
    private val playerView: CivView,
    private val refreshParent: () -> Unit
) : Table(BaseScreen.skin) {
    private var message = ""
    private var showHistory = false

    init { top().left(); render() }

    private fun refresh() { render(); refreshParent() }

    private fun render() {
        clearChildren()
        defaults().pad(5f)
        val currentCiv = playerView.getCiv()
        val game = currentCiv.gameInfo

        add("세계 군수 시장 · 시범 운영".toLabel(fontSize = Constants.headingFontSize)).left().growX().row()
        add("발주 → 생산 → 판매국 인도하기 → 구매국 인도받기".toLabel()).left().row()
        add("유닛은 생산국 도시에 그대로 생성되고, 인도 후 구매국이 직접 이동합니다.".toLabel()).left().row()
        add("대금은 주문 때 예치하고, 인도받기가 완료되면 판매국에 지급합니다.".toLabel()).left().row()
        add("내 보유 금: ${currentCiv.gold}G".toLabel()).left().row()
        if (message.isNotEmpty()) add(message.toLabel()).left().row()

        val historyButton = TextButton(if (showHistory) "군수 목록" else "군수 거래 내역", BaseScreen.skin)
        historyButton.onClick { showHistory = !showHistory; refresh() }
        add(historyButton).left().row()
        if (showHistory) {
            val records = game.militaryMarketHistory.asReversed().take(20)
            add("군수 거래 기록 (최근 ${records.size}건, 최대 100건 보관)".toLabel()).left().row()
            if (records.isEmpty()) add("아직 군수 거래 기록이 없습니다.".toLabel()).left().row()
            for (record in records) {
                val seller = game.getCivilizationOrNull(record.sellerCivId)?.civName ?: "알 수 없음"
                val buyer = game.getCivilizationOrNull(record.buyerCivId)?.civName ?: "알 수 없음"
                add("${record.turn}턴 · ${seller} → ${buyer} · ${record.unitName} · ${record.price}G · ${record.status}"
                    .toLabel(hideIcons = true)).left().row()
            }
            return
        }

        add("내 발주 주문".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        val buying = MilitaryMarket.myContracts(currentCiv)
        if (buying.isEmpty()) add("현재 진행 중인 발주가 없습니다.".toLabel()).left().row()
        for (contract in buying) {
            val seller = game.getCivilizationOrNull(contract.sellerCivId)?.civName ?: "알 수 없음"
            val line = Table(BaseScreen.skin)
            line.add("${contract.unitName} · ${seller} · ${contract.price}G · ${MilitaryMarket.statusLabel(contract)}"
                .toLabel(hideIcons = true)).growX().left()

            when (contract.status) {
                MilitaryMarket.PRODUCING -> {
                    val cancelButton = TextButton("주문 취소", BaseScreen.skin)
                    cancelButton.isDisabled = !GUI.isAllowedChangeState() || !currentCiv.isHuman()
                    cancelButton.onClick {
                        message = if (GUI.isAllowedChangeState() && MilitaryMarket.cancel(currentCiv, contract.id))
                            "생산 중 주문 취소 · 예치금 환불" else "취소할 수 없습니다."
                        refresh()
                    }
                    line.add(cancelButton)
                }
                MilitaryMarket.AWAITING_RECEIPT -> {
                    val receiveButton = TextButton("인도받기", BaseScreen.skin)
                    receiveButton.isDisabled = !GUI.isAllowedChangeState() ||
                        !currentCiv.isHuman() || !MilitaryMarket.canReceive(currentCiv, contract)
                    receiveButton.onClick {
                        message = if (GUI.isAllowedChangeState() && MilitaryMarket.receive(currentCiv, contract.id))
                            "인도 완료 · 생산기지에서 출발 가능합니다 (다음 턴부터 이동)"
                        else "인도 불가: 통행 허가·영토·생산 유닛 상태를 확인하세요."
                        refresh()
                    }
                    line.add(receiveButton)
                }
            }
            add(line).growX().row()
        }

        // Seller-side manual dispatch applies to any player-seller contracts introduced later.
        // Current phase 1 accepts player -> AI orders; those AI producers dispatch automatically.
        val selling = MilitaryMarket.sellingContracts(currentCiv)
        if (selling.isNotEmpty()) {
            add("내가 생산한 납품 주문".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
            for (contract in selling) {
                val line = Table(BaseScreen.skin)
                line.add("${contract.unitName} · ${MilitaryMarket.statusLabel(contract)}"
                    .toLabel(hideIcons = true)).growX().left()
                if (contract.status == MilitaryMarket.AWAITING_DISPATCH) {
                    val dispatchButton = TextButton("인도하기", BaseScreen.skin)
                    dispatchButton.isDisabled = !GUI.isAllowedChangeState() || !currentCiv.isHuman()
                    dispatchButton.onClick {
                        message = if (GUI.isAllowedChangeState() && MilitaryMarket.dispatch(currentCiv, contract.id))
                            "인도 승인 · 구매국의 인도받기를 기다립니다."
                        else "인도 승인 실패: 유닛 상태를 확인하세요."
                        refresh()
                    }
                    line.add(dispatchButton)
                }
                add(line).growX().row()
            }
        }

        add("생산 가능한 유닛 목록".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        if (!currentCiv.isHuman() || playerView.isSpectator()) {
            add("관전자 화면에서는 발주할 수 없습니다.".toLabel()).left().row()
            return
        }

        val offers = MilitaryMarket.offersFor(currentCiv)
        if (offers.isEmpty()) add("현재 발주 가능한 AI 생산국이 없습니다.".toLabel()).left().row()
        for (offer in offers) {
            val seller = game.getCivilizationOrNull(offer.sellerId) ?: continue
            val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: continue
            val line = Table(BaseScreen.skin)
            line.add("${offer.unitName} · ${seller.civName} (${city.name})"
                .toLabel(hideIcons = true)).growX().left()
            line.add("${offer.price}G".toLabel()).padRight(6f)
            val orderButton = TextButton("발주", BaseScreen.skin)
            orderButton.isDisabled = !GUI.isAllowedChangeState() || currentCiv.gold < offer.price
            orderButton.onClick {
                message = if (GUI.isAllowedChangeState() && MilitaryMarket.order(currentCiv, offer))
                    "${offer.unitName} 발주 완료 · 생산 대기열 등록"
                else "발주 실패: 금액·생산 조건·외교 관계 확인"
                refresh()
            }
            line.add(orderButton).width(80f)
            add(line).growX().row()
        }
        if (offers.size == 100) add("표시 성능을 위해 최대 100개 상품을 보여줍니다.".toLabel()).left().row()
    }
}
