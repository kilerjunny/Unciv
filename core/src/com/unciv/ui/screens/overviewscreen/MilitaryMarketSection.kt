package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.MilitaryMarket
import com.unciv.logic.market.MilitaryMarketAI
import com.unciv.logic.city.City
import com.unciv.models.translations.tr
import com.unciv.models.ruleset.unit.BaseUnit
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
    private var searchQuery = ""

    init { top().left(); render() }
    private fun refresh() { render(); refreshParent() }
    /** Active contracts use Unciv's own production-turn calculation.
     * For queued production, add time for earlier queue entries.
     * Estimates change when production or the queue changes.
     */
    private fun productionEta(city: City?, unitName: String, activeContract: Boolean): String {
        if (city == null) return "생산 턴 확인 불가"
        val constructions = city.cityConstructions
        if (activeContract) {
            val queue = constructions.constructionQueue
            val position = queue.indexOf(unitName)
            if (position >= 0) {
                val unitTurns = constructions.turnsToConstruction(unitName)
                if (unitTurns < 0 || unitTurns >= 100000) return "생산 턴 계산 불가"
                if (position == 0) return "남은 약 ${unitTurns}턴"
                var precedingTurns = 0L
                for (prior in queue.take(position)) {
                    val turns = constructions.turnsToConstruction(prior)
                    if (turns < 0 || turns >= 100000) return "생산 턴 계산 불가"
                    precedingTurns += turns
                }
                val total = precedingTurns + unitTurns
                return "남은 약 ${total}턴 (대기 ${position}건)"
            }
            return "생산 대기열 확인 필요"
        }
        // Fast preview: avoid recalculating full city stats for every advertised unit.
        val unit = city.civ.gameInfo.ruleset.units[unitName] ?: return "생산 턴 확인 불가"
        val production = city.cityStats.currentCityStats.production.toDouble()
        if (production <= 0.0 || !production.isFinite()) return "생산 턴 계산 불가"
        val workLeft = (unit.getProductionCost(city.civ, city) - constructions.getWorkDone(unitName))
            .coerceAtLeast(0)
        val turns = kotlin.math.ceil(workLeft / production).toLong().coerceAtLeast(1L)
        return "생산 약 ${turns}턴"
    }


    private fun render() {
        clearChildren()
        defaults().pad(5f)
        val civ = playerView.getCiv()
        val game = civ.gameInfo
        val editable = GUI.isAllowedChangeState() && civ.isHuman() && !playerView.isSpectator()

        add("세계 군수 시장".toLabel(fontSize = Constants.headingFontSize)).left().growX().row()
        add("국가 간 유닛 주문 생산 · 가격 자동 책정 · 대금 예치".toLabel()).left().row()
        add("발주 → 판매국 수락 → 생산 → 인도하기 → 인도받기 → 생산기지에서 이동".toLabel()).left().row()
        add("AI 판매국은 주문을 자동 수락하며, 플레이어 판매국은 직접 수락·거절합니다.".toLabel()).left().row()
        add("군수 인도는 생산국 영토 통행 허가가 필요할 수 있습니다.".toLabel()).left().row()
        add("생산 턴 수는 예상치이며, 도시 생산력·생산 대기열 변경 시 달라질 수 있습니다.".toLabel()).left().row()
        add("현재 ${game.turns}턴 · 내 보유 금: ${civ.gold}G".toLabel()).left().row()
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
            val sellerCity = game.getCivilizationOrNull(contract.sellerCivId)
                ?.cities?.firstOrNull { it.id == contract.sellerCityId }
            val eta = if (contract.status == MilitaryMarket.PRODUCING)
                " · ${productionEta(sellerCity, contract.unitName, true)}" else ""
            line.add("${contract.unitName.tr()} · $seller · ${contract.price}G · ${MilitaryMarket.statusLabel(contract)}$eta"
                .toLabel(hideIcons = true)).growX().left()
            when (contract.status) {
                MilitaryMarket.PENDING_APPROVAL, MilitaryMarket.PRODUCING -> {
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
            val sellerCity = civ.cities.firstOrNull { it.id == contract.sellerCityId }
            val eta = if (contract.status == MilitaryMarket.PRODUCING)
                " · ${productionEta(sellerCity, contract.unitName, true)}" else ""
            line.add("${contract.unitName.tr()} · $buyer · ${contract.price}G · ${MilitaryMarket.statusLabel(contract)}$eta"
                .toLabel(hideIcons = true)).growX().left()
            if (contract.status == MilitaryMarket.PENDING_APPROVAL) {
                val approveButton = TextButton("주문 수락", BaseScreen.skin)
                approveButton.isDisabled = !editable
                approveButton.onClick {
                    message = if (editable && MilitaryMarket.approveOrder(civ, contract.id))
                        "주문 수락 완료 · 생산 대기열에 등록했습니다."
                    else "수락 실패 · 외교 관계·생산 가능 여부·대기열을 확인하세요."
                    refresh()
                }
                line.add(approveButton)
                val rejectButton = TextButton("주문 거부", BaseScreen.skin)
                rejectButton.isDisabled = !editable
                rejectButton.onClick {
                    message = if (editable && MilitaryMarket.rejectOrder(civ, contract.id))
                        "주문 거절 완료 · 구매국에 예치금을 전액 환불했습니다."
                    else "이미 처리됐거나 거절할 수 없는 주문입니다."
                    refresh()
                }
                line.add(rejectButton)
            }
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
        else add(MilitaryMarketAI.sellerDemandSummary(civ).toLabel(hideIcons = true)).left().row()
        for (listing in listings) {
            val city = civ.cities.firstOrNull { it.id == listing.sellerCityId }
            val line = Table(BaseScreen.skin)
            val listedUnit = game.ruleset.units[listing.unitName]
            val label = if (listedUnit?.uniqueTo != null || listedUnit?.replaces != null) "★ 고유 병종 · " else ""
            line.add("$label${listing.unitName.tr()} · ${city?.name?.tr() ?: "도시 없음"} · ${listing.price}G · ${productionEta(city, listing.unitName, false)}"
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
                add("도시마다 한 유닛 종류를 등록할 수 있습니다. 주문 수락 후 생산합니다.".toLabel()).left().row()
                for (city in civ.cities) {
                    if (listings.any { it.sellerCityId == city.id } ||
                        selling.any { it.sellerCityId == city.id } ||
                        city.isPuppet || city.isInResistance() || city.isBeingRazed) continue
                    // Show the seller's unique units first; the old 12-item cap could hide them.
                    for (unit in city.cityConstructions.getConstructableUnits()
                        .filter { it.isMilitary && !it.isGreatPerson }
                        .sortedWith(compareByDescending<BaseUnit> { it.uniqueTo != null || it.replaces != null }
                            .thenBy { it.name })
                        .take(24)) {
                        val line = Table(BaseScreen.skin)
                        val price = MilitaryMarket.referencePrice(civ, city, unit)
                        val label = if (unit.uniqueTo != null || unit.replaces != null) "★ 고유 병종 · " else ""
                        line.add("$label${unit.name.tr()} · ${city.name.tr()} · ${price}G · ${productionEta(city, unit.name, false)}"
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
        val searchBar = Table(BaseScreen.skin)
        val searchField = TextField(searchQuery, BaseScreen.skin)
        searchField.messageText = "유닛명 / 국가 / 도시 검색"
        searchBar.add(searchField).width(260f).padRight(4f)
        val searchButton = TextButton("검색", BaseScreen.skin)
        searchButton.onClick {
            searchQuery = searchField.text.trim()
            refresh()
        }
        searchBar.add(searchButton)
        val clearButton = TextButton("초기화", BaseScreen.skin)
        clearButton.onClick {
            searchQuery = ""
            refresh()
        }
        searchBar.add(clearButton)
        add(searchBar).left().row()
        if (!civ.isHuman() || playerView.isSpectator()) {
            add("관전자 화면에서는 발주할 수 없습니다.".toLabel()).left().row()
            return
        }
        // Expand candidates only for an explicit search; normal browsing stays capped.
        val query = searchQuery.trim()
        val offers = MilitaryMarket.offersFor(civ, limit = if (query.isEmpty()) 100 else 500)
            .asSequence().filter { offer ->
                if (query.isEmpty()) return@filter true
                val seller = game.getCivilizationOrNull(offer.sellerId) ?: return@filter false
                val city = seller.cities.firstOrNull { it.id == offer.cityId }
                listOf(offer.unitName, offer.unitName.tr(), seller.civName,
                    seller.civName.tr(), city?.name.orEmpty(), city?.name?.tr().orEmpty())
                    .any { it.contains(query, ignoreCase = true) }
            }.take(100).toList()
        if (offers.isEmpty()) add((if (query.isEmpty())
            "현재 발주 가능한 판매 상품이 없습니다." else "검색 결과가 없습니다.").toLabel()).left().row()
        for (offer in offers) {
            val seller = game.getCivilizationOrNull(offer.sellerId) ?: continue
            val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: continue
            val line = Table(BaseScreen.skin)
            val offeredUnit = game.ruleset.units[offer.unitName]
            val label = if (offeredUnit?.uniqueTo != null || offeredUnit?.replaces != null) "★ 고유 병종 · " else ""
            line.add("$label${offer.unitName.tr()} · ${seller.civName.tr()} (${city.name.tr()}) · ${productionEta(city, offer.unitName, false)}"
                .toLabel(hideIcons = true)).growX().left()
            line.add("${offer.price}G".toLabel()).padRight(6f)
            val button = TextButton("발주", BaseScreen.skin)
            button.isDisabled = !editable || civ.gold < offer.price
            button.onClick {
                message = if (editable && MilitaryMarket.order(civ, offer))
                    "${offer.unitName.tr()} 발주 완료 · 판매국 처리 대기 또는 생산 시작"
                else "발주 실패 · 금액·생산 조건·외교 관계를 확인하세요."
                refresh()
            }
            line.add(button).width(80f)
            add(line).growX().row()
        }
        if (offers.size >= 100) add("화면 성능을 위해 최대 100개 상품을 표시합니다.".toLabel()).left().row()
    }
}
