package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.MilitaryMarket
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

/** Korean procurement marketplace. Both sides approve delivery independently. */
class MilitaryMarketSection(
    private val playerView: CivView,
    private val refreshParent: () -> Unit
) : Table(BaseScreen.skin) {
    private var message = ""
    private var showHistory = false
    private var showSellRegistration = false

    init { top().left(); render() }
    private fun refresh() { render(); refreshParent() }

    private fun render() {
        clearChildren()
        defaults().pad(5f)
        val civ = playerView.getCiv()
        val game = civ.gameInfo
        val editable = GUI.isAllowedChangeState() && civ.isHuman() && !playerView.isSpectator()

        add("세계 군수 시장".toLabel(fontSize = Constants.headingFontSize)).left().growX().row()
        add("국가 간 유닛 주문 생산 · 가격 자동 책정 · 대금 예치".toLabel()).left().row()
        add("발주 → 생산 → 판매국 인도하기 → 구매국 인도받기 → 생산기지에서 이동".toLabel()).left().row()
        add("군수 인도는 생산국 영토 통행 허가가 필요할 수 있습니다.".toLabel()).left().row()
        add("내 보유 금: ${civ.gold}G".toLabel()).left().row()
        if (message.isNotEmpty()) add(message.toLabel()).left().row()

        val historyButton = TextButton(if (showHistory) "시장 목록" else "군수 거래 기록", BaseScreen.skin)
        historyButton.onClick { showHistory = !showHistory; refresh() }
        add(historyButton).left().row()
        if (showHistory) {
            val records = game.militaryMarketHistory.asReversed()
                .filter { it.buyerCivId == civ.civID || it.sellerCivId == civ.civID }
                .take(20)
            add("최근 군수 거래 ${records.size}건 (최대 100건 보관)".toLabel()).left().row()
            if (records.isEmpty()) add("아직 군수 거래 기록이 없습니다.".toLabel()).left().row()
            for (record in records) {
                val seller = game.getCivilizationOrNull(record.sellerCivId)?.civName?.tr() ?: "알 수 없음"
                val buyer = game.getCivilizationOrNull(record.buyerCivId)?.civName?.tr() ?: "알 수 없음"
                add("${record.turn}턴 · $seller → $buyer · ${record.unitName.tr()} · ${record.price}G · ${record.status}"
                    .toLabel(hideIcons = true)).left().row()
            }
            return
        }

        add("내 발주 계약".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        val buying = MilitaryMarket.myContracts(civ)
        if (buying.isEmpty()) add("진행 중인 발주 계약이 없습니다.".toLabel()).left().row()
        for (contract in buying) {
            val seller = game.getCivilizationOrNull(contract.sellerCivId)?.civName?.tr() ?: "알 수 없음"
            val line = Table(BaseScreen.skin)
            line.add("${contract.unitName.tr()} · $seller · ${contract.price}G · ${MilitaryMarket.statusLabel(contract)}"
                .toLabel(hideIcons = true)).growX().left()
            when (contract.status) {
                MilitaryMarket.PRODUCING -> {
                    val cancelButton = TextButton("주문 취소", BaseScreen.skin)
                    cancelButton.isDisabled = !editable
                    cancelButton.onClick {
                        message = if (editable && MilitaryMarket.cancel(civ, contract.id))
                            "주문 취소 완료 · 예치금 전액 환불" else "주문을 취소할 수 없습니다."
                        refresh()
                    }
                    line.add(cancelButton)
                }
                MilitaryMarket.AWAITING_RECEIPT -> {
                    val receiveButton = TextButton("인도받기", BaseScreen.skin)
                    receiveButton.isDisabled = !editable || !MilitaryMarket.canReceive(civ, contract)
                    receiveButton.onClick {
                        message = if (editable && MilitaryMarket.receive(civ, contract.id))
                            "인도 완료 · 생산기지에서 다음 턴부터 직접 이동하세요."
                        else "인도 실패 · 통행권·생산 유닛 상태를 확인하세요."
                        refresh()
                    }
                    line.add(receiveButton)
                }
            }
            add(line).growX().row()
        }

        add("내가 납품할 계약".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        val selling = MilitaryMarket.sellingContracts(civ)
        if (selling.isEmpty()) add("진행 중인 납품 계약이 없습니다.".toLabel()).left().row()
        for (contract in selling) {
            val buyer = game.getCivilizationOrNull(contract.buyerCivId)?.civName?.tr() ?: "알 수 없음"
            val line = Table(BaseScreen.skin)
            line.add("${contract.unitName.tr()} · $buyer · ${contract.price}G · ${MilitaryMarket.statusLabel(contract)}"
                .toLabel(hideIcons = true)).growX().left()
            if (contract.status == MilitaryMarket.AWAITING_DISPATCH) {
                val dispatchButton = TextButton("인도하기", BaseScreen.skin)
                dispatchButton.isDisabled = !editable
                dispatchButton.onClick {
                    message = if (editable && MilitaryMarket.dispatch(civ, contract.id))
                        "인도 승인 완료 · 구매국의 인도받기를 기다립니다."
                    else "인도 승인 실패 · 생산된 유닛 상태를 확인하세요."
                    refresh()
                }
                line.add(dispatchButton)
            }
            add(line).growX().row()
        }

        add("내 판매 등록 상품".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        val listings = MilitaryMarket.myListings(civ)
        if (listings.isEmpty()) add("등록한 판매 상품이 없습니다.".toLabel()).left().row()
        for (listing in listings) {
            val city = civ.cities.firstOrNull { it.id == listing.sellerCityId }
            val line = Table(BaseScreen.skin)
            line.add("${listing.unitName.tr()} · ${city?.name?.tr() ?: "도시 없음"} · ${listing.price}G"
                .toLabel(hideIcons = true)).growX().left()
            val withdraw = TextButton("등록 취소", BaseScreen.skin)
            withdraw.isDisabled = !editable
            withdraw.onClick {
                message = if (editable && MilitaryMarket.withdrawListing(civ, listing.id))
                    "판매 등록을 취소했습니다." else "판매 등록을 취소할 수 없습니다."
                refresh()
            }
            line.add(withdraw)
            add(line).growX().row()
        }

        if (editable) {
            val toggle = TextButton(if (showSellRegistration) "판매 등록 접기" else "판매 상품 등록", BaseScreen.skin)
            toggle.onClick { showSellRegistration = !showSellRegistration; refresh() }
            add(toggle).left().row()
            if (showSellRegistration) {
                add("도시마다 한 유닛 종류를 등록할 수 있습니다. 주문 접수 후 실제 생산합니다.".toLabel()).left().row()
                for (city in civ.cities) {
                    if (listings.any { it.sellerCityId == city.id } ||
                        selling.any { it.sellerCityId == city.id } ||
                        city.isPuppet || city.isInResistance() || city.isBeingRazed) continue
                    for (unit in city.cityConstructions.getConstructableUnits()
                        .filter { it.isMilitary && !it.isGreatPerson }
                        .take(12)) {
                        val line = Table(BaseScreen.skin)
                        val price = MilitaryMarket.referencePrice(civ, city, unit)
                        line.add("${unit.name.tr()} · ${city.name.tr()} · ${price}G"
                            .toLabel(hideIcons = true)).growX().left()
                        val button = TextButton("판매 등록", BaseScreen.skin)
                        button.onClick {
                            message = if (MilitaryMarket.listForSale(civ, city.id, unit.name))
                                "${unit.name.tr()} 판매 등록 완료"
                            else "등록 불가 · 도시 생산 대기열·기존 계약을 확인하세요."
                            refresh()
                        }
                        line.add(button)
                        add(line).growX().row()
                    }
                }
            }
        }

        add("다른 국가의 판매 상품".toLabel(fontSize = Constants.headingFontSize)).left().padTop(12f).row()
        if (!civ.isHuman() || playerView.isSpectator()) {
            add("관전자 화면에서는 발주할 수 없습니다.".toLabel()).left().row()
            return
        }
        val offers = MilitaryMarket.offersFor(civ)
        if (offers.isEmpty()) add("현재 발주 가능한 판매 상품이 없습니다.".toLabel()).left().row()
        for (offer in offers) {
            val seller = game.getCivilizationOrNull(offer.sellerId) ?: continue
            val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: continue
            val line = Table(BaseScreen.skin)
            line.add("${offer.unitName.tr()} · ${seller.civName.tr()} (${city.name.tr()})"
                .toLabel(hideIcons = true)).growX().left()
            line.add("${offer.price}G".toLabel()).padRight(6f)
            val button = TextButton("발주", BaseScreen.skin)
            button.isDisabled = !editable || civ.gold < offer.price
            button.onClick {
                message = if (editable && MilitaryMarket.order(civ, offer))
                    "${offer.unitName.tr()} 발주 완료 · 생산 대기열 등록"
                else "발주 실패 · 금액·생산 조건·외교 관계를 확인하세요."
                refresh()
            }
            line.add(button).width(80f)
            add(line).growX().row()
        }
        if (offers.size >= 100) add("화면 성능을 위해 최대 100개 상품을 표시합니다.".toLabel()).left().row()
    }
}
