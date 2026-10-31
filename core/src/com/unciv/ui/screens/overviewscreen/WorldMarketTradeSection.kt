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
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.translations.tr
import com.unciv.logic.automation.civilization.GrandStrategyPerf
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
    private var performanceOpen = false
    private var militaryMarketOpen = false
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
            if (historyOpen) { performanceOpen = false; militaryMarketOpen = false }
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        add(historyButton).left().row()

        val perfButton = TextButton(
            if (performanceOpen) "시장 보기" else "성능 진단",
            BaseScreen.skin
        )
        perfButton.onClick {
            performanceOpen = !performanceOpen
            if (performanceOpen) { historyOpen = false; militaryMarketOpen = false }
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        add(perfButton).left().row()

        val militaryButton = TextButton(
            if (militaryMarketOpen) "시장 보기" else "군수 시장", BaseScreen.skin
        )
        militaryButton.onClick {
            militaryMarketOpen = !militaryMarketOpen
            if (militaryMarketOpen) { historyOpen = false; performanceOpen = false }
            render()
            parentTab.overviewScreen.resizePage(parentTab)
        }
        add(militaryButton).left().row()
        if (militaryMarketOpen) {
            add(MilitaryMarketSection(playerView) {
                parentTab.overviewScreen.resizePage(parentTab)
            }).growX().row()
            return
        }

        if (performanceOpen) {
            val gameId = playerView.getCiv().gameInfo.gameId
            for (line in GrandStrategyPerf.reportLines(gameId)) {
                add(line.toLabel(hideIcons = true)).left().row()
            }
            return
        }

        if (historyOpen) {
            val game = playerView.getCiv().gameInfo
            val records = WorldMarketHistory.recent(game)
            add("최근 세계 시장 거래 ${records.size}건 (최대 100건)".toLabel()).left().row()
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
