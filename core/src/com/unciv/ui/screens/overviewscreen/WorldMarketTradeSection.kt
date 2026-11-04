package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.scenes.scene2d.ui.TextField
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.WorldMarket
import com.unciv.logic.market.WorldMarketTrading
import com.unciv.logic.market.WorldMarketOrderPurchase
import com.unciv.logic.market.WorldMarketHistory
import com.unciv.logic.market.WorldMarketRouteStats
import com.unciv.logic.market.WorldMarketMainRoutes
import com.unciv.logic.market.WorldMarketVirtualCarriers
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.translations.tr
import com.unciv.logic.automation.civilization.GrandStrategyPerf
import com.unciv.logic.automation.civilization.GrandStrategyThermal
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

class WorldMarketTradeSection(
    private val playerView: CivView,
    private val parentTab: EmpireOverviewTab,
    private val merchantMode: Boolean = false // GS_MERCHANT_OVERVIEW_TAB_V1
) : Table(BaseScreen.skin) {

    private var expanded = true
    private var historyOpen = false
    private var performanceOpen = false
    private var militaryMarketOpen = false
    private var tradeMapOpen = merchantMode
    private var tradeMapOnlyMine = false
    private var tradeMapLifetime = false
    private var tradeMapMode = "ALL"
    private var tradeMapLowPower = true // GS_TRADE_MAP_COOL_V1: default low heat on mobile
    private var tradeMapMainOnly = false // GS_WORLD_MAIN_ROUTES_V1
    private var tradeMapCarrierView = false // GS_VIRTUAL_MERCHANT_NETWORK_V1
    private var message = ""
    // GS_WORLD_MARKET_V14_SEARCH
    private var resourceSearch = ""
    // GS_MARKET_COUNTRY_GROUPS_V15_WORLD
    private val expandedSellerIds = mutableSetOf<String>()
    private var affordableOnly = false
    private var strategicOnly = false

    init {
        top().left()
        render()
    }

    private fun render() {
        clearChildren()
        defaults().pad(5f)

        val header = Table(BaseScreen.skin)
        header.add((if (merchantMode) "상단 · 무역 정보" else "세계 시장").toLabel(
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

        if (!merchantMode) {
            val resourcesButton = TextButton("자원 현황", BaseScreen.skin)
            resourcesButton.onClick {
                parentTab.overviewScreen.select(EmpireOverviewCategories.Resources, "")
            }
            header.add(resourcesButton).padLeft(5f)
        }

        add(header).growX().row()

        if (message.isNotEmpty())
            add(message.toLabel()).left().growX().row()

        if (!expanded) return

        // GS_MERCHANT_OVERVIEW_TAB_V1: trade contracts stay in Trades,
        // while routes, logistics, records and diagnostics live in Merchants.
        if (merchantMode) {
            val viewer = playerView.getCiv()
            val recentRecords = WorldMarketHistory.recent(viewer.gameInfo)
            val tollEarned = recentRecords.sumOf { record ->
                record.cashTollPayments.filter { it.civId == viewer.civID }.sumOf { it.gold }
            }
            val tollPaid = recentRecords.filter { it.sellerCivId == viewer.civID }
                .sumOf { it.cashTollGold }
            val amount = recentRecords.sumOf { it.quantity }
            add("최근 거래 기록 ${recentRecords.size}건 · 물동량 ${amount}개 · 내 통행료 수입 ${tollEarned}G / 지출 ${tollPaid}G".toLabel(hideIcons = true))
                .left().growX().row()
            add("현재 육상 상단·연안/원양 상선은 가상 운송 구간입니다. 독립적인 상단 AI 운항은 아직 미구현입니다.".toLabel(hideIcons = true))
                .left().growX().row()

            val pages = Table(BaseScreen.skin)
            val mapButton = TextButton(if (tradeMapOpen) "무역 지도 ✓" else "무역 지도", BaseScreen.skin)
            mapButton.onClick {
                tradeMapOpen = true
                historyOpen = false
                performanceOpen = false
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            pages.add(mapButton).padRight(5f)

            val historyButton = TextButton(if (historyOpen) "운송 내역 ✓" else "운송 내역", BaseScreen.skin)
            historyButton.onClick {
                historyOpen = true
                tradeMapOpen = false
                performanceOpen = false
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            pages.add(historyButton).padRight(5f)

            val perfButton = TextButton(if (performanceOpen) "성능 진단 ✓" else "성능 진단", BaseScreen.skin)
            perfButton.onClick {
                performanceOpen = true
                tradeMapOpen = false
                historyOpen = false
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            pages.add(perfButton)
            add(pages).left().row()
        } else {
            val merchantButton = TextButton("상단 · 무역 지도", BaseScreen.skin)
            merchantButton.onClick {
                parentTab.overviewScreen.select(EmpireOverviewCategories.Merchants, "")
            }
            add(merchantButton).left().row()

            val militaryButton = TextButton(
                if (militaryMarketOpen) "시장 보기" else "군수 시장", BaseScreen.skin
            )
            militaryButton.onClick {
                militaryMarketOpen = !militaryMarketOpen
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            add(militaryButton).left().row()
        }
        if (militaryMarketOpen) {
            add(MilitaryMarketSection(playerView) {
                parentTab.overviewScreen.resizePage(parentTab)
            }).growX().row()
            return
        }

        if (performanceOpen) {
            // GS_THERMAL_MONITOR_V1: Refresh only on tap, not every render frame.
            val refresh = TextButton("발열 새로고침", BaseScreen.skin)
            refresh.onClick {
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            add(refresh).left().row()
            for (line in GrandStrategyThermal.reportLines()) {
                add(line.toLabel(hideIcons = true)).left().row()
            }
            add("---- 기존 턴 성능 진단 ----".toLabel(hideIcons = true)).left().row()
            val gameId = playerView.getCiv().gameInfo.gameId
            for (line in GrandStrategyPerf.reportLines(gameId)) {
                add(line.toLabel(hideIcons = true)).left().row()
            }
            return
        }

        // GS_TRADE_MAP_SPEED_V1: geographic summary; never runs the route planner.
        if (tradeMapOpen) {
            val viewer = playerView.getCiv()
            val game = viewer.gameInfo
            val spectator = playerView.isSpectator()
            val window = WorldMarketRouteStats.windowTurns(game)
            val scopeButton = TextButton(if (tradeMapOnlyMine) "내 거래만 ✓" else "전체 국가", BaseScreen.skin)
            scopeButton.onClick {
                tradeMapOnlyMine = !tradeMapOnlyMine
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            val periodButton = TextButton(if (tradeMapLifetime) "기록 전체" else "최근 ${window}턴", BaseScreen.skin)
            periodButton.onClick {
                tradeMapLifetime = !tradeMapLifetime
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            val modeButton = TextButton(when (tradeMapMode) {
                "LAND" -> "육로만"
                "SEA" -> "항로만"
                "MIXED" -> "복합만"
                else -> "모든 경로"
            }, BaseScreen.skin)
            modeButton.onClick {
                tradeMapMode = when (tradeMapMode) {
                    "ALL" -> "LAND"
                    "LAND" -> "SEA"
                    "SEA" -> "MIXED"
                    else -> "ALL"
                }
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            val switches = Table(BaseScreen.skin)
            switches.add(periodButton).padRight(5f)
            switches.add(scopeButton).padRight(5f)
            switches.add(modeButton)
            add(switches).left().row()
            // A separate row avoids overflowing narrow Android landscape layouts.
            val powerButton = TextButton(if (tradeMapLowPower) "절전 모드 ✓" else "절전 모드 OFF", BaseScreen.skin)
            powerButton.onClick {
                tradeMapLowPower = !tradeMapLowPower
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            add(powerButton).left().row()
            // World routes share a global cap; player/country filters only affect
            // ordinary route rendering, not corridor discovery.
            val mainOnlyButton = TextButton(
                if (tradeMapMainOnly) "주무역로만 ✓" else "일반 경로 + 주무역로",
                BaseScreen.skin
            )
            mainOnlyButton.onClick {
                tradeMapMainOnly = !tradeMapMainOnly
                if (tradeMapMainOnly) tradeMapCarrierView = false
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            add(mainOnlyButton).left().row()
            // GS_VIRTUAL_MERCHANT_NETWORK_V1: display only; no new routes or units.
            val carrierButton = TextButton(
                if (tradeMapCarrierView) "가상 상선망 ✓" else "가상 상선망 보기", BaseScreen.skin
            )
            carrierButton.onClick {
                tradeMapCarrierView = !tradeMapCarrierView
                if (tradeMapCarrierView) tradeMapMainOnly = false
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            add(carrierButton).left().row()

            val knownIds = viewer.getKnownCivs().map { it.civID }.toSet() + viewer.civID
            val entries = (if (tradeMapLifetime) WorldMarketRouteStats.lifetimeRows(game)
                else WorldMarketRouteStats.windowRows(game))
                .filter { row ->
                    (spectator || (row.sellerCivId in knownIds && row.buyerCivId in knownIds)) &&
                    (!tradeMapOnlyMine || row.sellerCivId == viewer.civID || row.buyerCivId == viewer.civID) &&
                    (tradeMapMode == "ALL" || row.routeMode == tradeMapMode)
                }
                .sortedByDescending { it.tradeCount }
            // GS_WORLD_MAIN_ROUTES_V1: global backbone from observed world traffic.
            // Compute only when opening/re-rendering the map, not during each turn or draw.
            val globalRows = WorldMarketRouteStats.windowRows(game)
            val maximumMain = WorldMarketMainRoutes.maximumCount(game)
            val mainRoutes = WorldMarketMainRoutes.select(game, globalRows)
            add("세계 주무역로 ${mainRoutes.size}/${maximumMain}개 · 최근 ${window}턴 전 세계 교역량 기준".toLabel(hideIcons = true))
                .left().growX().row()
            // GS_CONNECTED_TRADE_BACKBONE_V2: summary only, never new pathfinding.
            add("연속 주무역로 총 ${mainRoutes.sumOf { it.tiles.size - 1 }}칸 · 긴 공유 항로 중심 선정".toLabel(hideIcons = true))
                .left().growX().row()
            val displayLimit = if (tradeMapLowPower) 24 else 60
            val visible = if (tradeMapMainOnly) emptyList() else entries.take(displayLimit)
            val summary = "거래 ${entries.sumOf { it.tradeCount }}건 · 물동량 ${entries.sumOf { it.quantity }}개 · " +
                "교역로 ${entries.size}개 집계 항목 (최대 ${displayLimit}개 경로 표시)"
            add(summary.toLabel(hideIcons = true)).left().growX().row()
            if (game.worldMarketRouteLifetimeTruncated && tradeMapLifetime)
                add("전체 누적 통계는 경로 400개 보존 제한으로 일부 누락되었습니다.".toLabel(hideIcons = true))
                    .left().growX().row()
            if (entries.isEmpty())
                add("표시할 무역로가 없습니다. 신규 세계 시장 거래 이후 기록됩니다.".toLabel(hideIcons = true))
                    .left().growX().row()
            val mapWidth = (parentTab.overviewScreen.stage.width - 65f).coerceIn(320f, 1100f)
            val mapHeight = (mapWidth * 0.54f).coerceIn(220f, 520f)
            add(WorldMarketRouteMapActor(game, viewer, spectator, visible, tradeMapLowPower, mainRoutes, tradeMapCarrierView))
                .width(mapWidth).height(mapHeight).left().row()
            val routeLegend = if (tradeMapCarrierView)
                "육상 상단: 노랑 · 연안 상선: 연한 파랑 · 원양 상선: 하늘색 · 환적: 분홍 · 주무역로: 연두색"
            else "육로: 노랑 · 항로: 하늘색 · 복합: 분홍 · 세계 주무역로: 굵은 연두색"
            add(routeLegend.toLabel(hideIcons = true))
                .left().growX().row()
            add("세계 무역로는 최소 운송지수 경로로 기록됩니다. 운송지수는 실제 금 차감이 아닙니다.".toLabel(hideIcons = true))
                .left().growX().row()
            add("탐험한 지도 칸만 표시하며, 과거 저장파일은 최근 거래 100건부터 복원됩니다.".toLabel(hideIcons = true))
                .left().growX().row()
            return
        }

        if (historyOpen) {
            val game = playerView.getCiv().gameInfo
            val records = WorldMarketHistory.recent(game)
            // GS_VIRTUAL_MERCHANT_NETWORK_V1: calculate once on tab opening.
            val backbone = WorldMarketVirtualCarriers.mainEdges(
                WorldMarketMainRoutes.select(game, WorldMarketRouteStats.windowRows(game))
            )
            add("최근 세계 시장 거래 ${records.size}건 (최대 100건)".toLabel()).left().row()
            // GS_WORLD_MARKET_CASH_TOLLS_V1: this is a recent-record subtotal, not lifetime income.
            val myCivId = playerView.getCiv().civID
            val myTollIncome = records.sumOf { record ->
                record.cashTollPayments.filter { it.civId == myCivId }.sumOf { it.gold }
            }
            val myTollPaid = records.filter { it.sellerCivId == myCivId }.sumOf { it.cashTollGold }
            add("최근 거래 기록 기준 내 통행료 수입 ${myTollIncome}G · 지출 ${myTollPaid}G".toLabel(hideIcons = true)).left().row()
            if (records.isEmpty())
                add("체결된 거래가 없습니다.".toLabel()).left().row()

            for (it in records) {
                val seller = game.getCivilizationOrNull(it.sellerCivId)?.civName?.tr() ?: "알 수 없음"
                val buyer = game.getCivilizationOrNull(it.buyerCivId)?.civName?.tr() ?: "알 수 없음"
                add("턴 ${it.turn}: ${seller} → ${buyer}".toLabel(hideIcons = true)).left().row()
                add("${it.resourceName.tr()} ${it.quantity}개 · ${it.totalGold}G".toLabel(hideIcons = true)).left().row()
                // Phase-1 debug view, before the dedicated route-map tab is introduced.
                if (it.routeMode != "UNKNOWN") {
                    val routeLabel = when (it.routeMode) {
                        "LAND" -> "육로"
                        "SEA" -> "항로"
                        "MIXED" -> "복합(육해상)"
                        else -> "경로 미확인"
                    }
                    val endpoints = if (it.originCityName.isNotBlank() && it.destinationCityName.isNotBlank())
                        "${it.originCityName.tr()} → ${it.destinationCityName.tr()} · " else ""
                    val steps = if (it.routeTiles.size >= 2) " · ${it.routeTiles.size - 1}칸" else ""
                    add("무역로: $endpoints$routeLabel$steps".toLabel(hideIcons = true)).left().row()
                    // GS_PRODUCTION_CITY_CAPITAL_V1: Explain origin selection, not physical delivery.
                    when (it.originKind) {
                        "PRODUCTION" -> add("출발: 현재 생산 도시(추정) · 도착: 구매국 수도".toLabel(hideIcons = true)).left().row()
                        "CAPITAL_FALLBACK" -> add("출발: 판매국 수도(생산지 미확인) · 도착: 구매국 수도".toLabel(hideIcons = true)).left().row()
                    }
                    // GS_ECONOMIC_TRADE_ROUTES_V1: index, not actual charged gold.
                    if (it.estimatedTransportCost >= 0)
                        add("추정 운송지수: ${it.estimatedTransportCost} (운송지수는 골드가 아님)".toLabel(hideIcons = true)).left().row()
                    // GS_TERRITORY_TOLLS_SUPPLY_V1: route choice only, no gold transfers.
                    if (it.supplyCost >= 0 && it.tollCost >= 0) {
                        add("보급료 ${it.supplyCost} · 통행료 ${it.tollCost} · 항구/환적 ${it.portCost} (운송지수)".toLabel(hideIcons = true)).left().row()
                        add("기본 ${it.terrainCost} - 주무역로 절감 ${it.mainSaving} + 위 비용 = 총 ${it.estimatedTransportCost}".toLabel(hideIcons = true)).left().row()
                    }
                    // GS_WORLD_MARKET_CASH_TOLLS_V1: actual gold from the seller's sale proceeds.
                    if (it.cashTollSettled) {
                        add("실제 통행료: ${it.cashTollGold}G · 판매국 순수입 ${it.totalGold - it.cashTollGold}G (구매국은 표기가격 지불)".toLabel(hideIcons = true)).left().row()
                        if (it.cashTollPayments.isNotEmpty()) {
                            val recipients = it.cashTollPayments.joinToString(" · ") { payout ->
                                val name = game.getCivilizationOrNull(payout.civId)?.civName?.tr() ?: payout.civId
                                "$name +${payout.gold}G"
                            }
                            add("통행국 국고 수입: $recipients".toLabel(hideIcons = true)).left().row()
                            // GS_MARITIME_PASSAGE_TRANSSHIPMENT_V2: real gold, not route indices.
                            val landCash = it.cashTollPayments.sumOf { p -> p.landGold }
                            val seaCash = it.cashTollPayments.sumOf { p -> p.seaGold }
                            val harborCash = it.cashTollPayments.sumOf { p -> p.harborGold }
                            if (landCash + seaCash + harborCash > 0)
                                add("실제 요금 구분: 육상 통행 ${landCash}G · 해상 통항 ${seaCash}G · 항구 환적 ${harborCash}G".toLabel(hideIcons = true)).left().row()
                        }
                    }
                    // GS_VIRTUAL_MERCHANT_NETWORK_V1: classification of recorded legs.
                    if (it.routeTiles.size >= 2) {
                        val legs = WorldMarketVirtualCarriers.summarize(game, it.routeTiles, backbone)
                        add("가상 운송 구간: 원양 ${legs.ocean} · 연안 ${legs.coast} · 육상 ${legs.land} · 환적 ${legs.transfers}".toLabel(hideIcons = true)).left().row()
                        add("현재 세계 주무역로 ${legs.main}칸 · 지선 ${legs.branches}칸".toLabel(hideIcons = true)).left().row()
                    }
                }
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

        // Native Android text input supports Korean IME composition.
        // The desktop path retains an inline text field.
        val searchBar = Table(BaseScreen.skin)
        if (Gdx.app.type == Application.ApplicationType.Android) {
            val nativeSearch = TextButton("자원·국가 검색 (한글 지원)", BaseScreen.skin)
            nativeSearch.onClick {
                Gdx.input.getTextInput(object : Input.TextInputListener {
                    override fun input(text: String) {
                        resourceSearch = text.trim()
                        render()
                        parentTab.overviewScreen.resizePage(parentTab)
                    }
                    override fun canceled() { }
                }, "세계 자원 시장 검색", resourceSearch, "자원명 / 판매국")
            }
            searchBar.add(nativeSearch).padRight(5f)
            if (resourceSearch.isNotEmpty())
                searchBar.add("검색: $resourceSearch".toLabel(hideIcons = true)).padRight(5f)
        } else {
            val searchField = TextField(resourceSearch, BaseScreen.skin)
            searchField.messageText = "자원명 또는 판매국"
            searchBar.add(searchField).width(260f).padRight(5f)
            val searchButton = TextButton("검색", BaseScreen.skin)
            searchButton.onClick {
                resourceSearch = searchField.text.trim()
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            searchBar.add(searchButton)
        }
        val clearSearch = TextButton("초기화", BaseScreen.skin)
        clearSearch.onClick {
            resourceSearch = ""
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        searchBar.add(clearSearch).padLeft(4f)
        add(searchBar).left().row()

        val filters = Table(BaseScreen.skin)
        val affordable = TextButton(if (affordableOnly) "구매 가능 금액 ✓" else "구매 가능 금액만", BaseScreen.skin)
        affordable.onClick {
            affordableOnly = !affordableOnly
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        filters.add(affordable).padLeft(5f)
        val strategic = TextButton(if (strategicOnly) "전략자원 ✓" else "전략자원만", BaseScreen.skin)
        strategic.onClick {
            strategicOnly = !strategicOnly
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        filters.add(strategic).padLeft(5f)
        add(filters).left().row()

        val query = resourceSearch.trim()
        val orders = gameInfo.worldMarketOrders
            .filter { order ->
                if (order.quantity <= 0 || order.unitPrice <= 0)
                    return@filter false

                val seller = gameInfo.civilizations.firstOrNull {
                    it.civID == order.sellerCivId
                } ?: return@filter false

                val resource = gameInfo.ruleset.tileResources[order.resourceName]
                val matchesSearch = query.isEmpty() || listOf(
                    order.resourceName, order.resourceName.tr(),
                    seller.civName, seller.civName.tr()
                ).any { it.contains(query, ignoreCase = true) }

                seller != buyer && seller.isAI() && !seller.isDefeated() &&
                    (spectator || seller.civName in known) &&
                    matchesSearch &&
                    (!affordableOnly || order.unitPrice <= buyer.gold) &&
                    (!strategicOnly || resource?.resourceType == ResourceType.Strategic)
            }
            .sortedWith(compareBy<com.unciv.logic.market.MarketSellOrder>(
                { it.sellerCivId }, { it.resourceName }, { it.unitPrice }, { it.id }))

        add("시장 등록 상품".toLabel(
            fontSize = Constants.headingFontSize
        )).left().growX().row()

        add("검색 결과 ${orders.size}건 · 판매국을 눌러 상품 보기".toLabel()).left().row()
        if (orders.isEmpty()) {
            add((if (query.isEmpty() && !affordableOnly && !strategicOnly)
                "현재 등록된 판매 상품이 없습니다." else "조건에 맞는 상품이 없습니다.").toLabel())
                .left().growX().row()
        }

        // Seller groups are closed by default; expand one to see its purchasable resources.
        val visibleOrders = orders.take(500)
        val bySeller = visibleOrders.groupBy { it.sellerCivId }
        // Display discovered city-states even with no sell order. This makes it
        // clear that no sellable surplus exists, rather than hiding the nation.
        // Keep the regular search/affordability/strategic filters unchanged.
        val emptyCityStateIds: List<String> = if (
            query.isEmpty() && !affordableOnly && !strategicOnly
        ) gameInfo.civilizations.asSequence()
            .filter { it.isCityState && it.isAI() && !it.isDefeated() }
            .filter { spectator || it.civName in known }
            .map { it.civID }
            .filter { !bySeller.containsKey(it) }
            .toList()
        else emptyList()

        val visibleSellerIds = (bySeller.keys + emptyCityStateIds)
            .distinct()
            .sortedBy { id ->
                gameInfo.civilizations.firstOrNull { it.civID == id }?.civName?.tr() ?: id
            }
        for (sellerId in visibleSellerIds) {
            val sellerOrders = bySeller[sellerId].orEmpty()
            val seller = gameInfo.civilizations.firstOrNull { it.civID == sellerId } ?: continue
            val expanded = sellerId in expandedSellerIds
            val sellerButton = TextButton(
                "${if (expanded) "접기" else "펼치기"} · ${seller.civName.tr()} · ${sellerOrders.size}종",
                BaseScreen.skin)
            sellerButton.onClick {
                if (!expandedSellerIds.add(sellerId)) expandedSellerIds.remove(sellerId)
                render()
                parentTab.overviewScreen.resizePage(parentTab)
            }
            add(sellerButton).left().growX().padTop(6f).row()
            if (!expanded) continue
            if (sellerOrders.isEmpty()) {
                add("현재 등록된 판매 자원이 없습니다 (자원 미확보·비축·거래 제한 가능).".toLabel())
                    .left().growX().row()
                continue
            }
            for (order in sellerOrders) {
                val line = Table(BaseScreen.skin)

                line.add(
                    ImageGetter.getResourcePortrait(
                        order.resourceName, 32f
                    )
                ).size(32f).padRight(5f)

                val owned = buyer.getResourceAmount(order.resourceName)
                val info = Table(BaseScreen.skin)

                info.add(
                    "${order.resourceName.tr()} · ${seller.civName.tr()}"
                        .toLabel(hideIcons = true)
                ).left().row()

                info.add(
                    "판매 ${order.quantity}개 · 내 가용 ${owned}개 · ${if (order.stockpiled) "비축" else "턴 공급"}"
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
                            "${order.resourceName.tr()} 1개 구매 완료"
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
        if (orders.size > 500)
            add("화면 성능을 위해 상품 500건만 표시합니다. 검색으로 범위를 좁혀주세요.".toLabel()).left().row()
    }
}
