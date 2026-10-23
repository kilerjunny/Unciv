package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.TextButton
import com.unciv.Constants
import com.unciv.GUI
import com.unciv.logic.market.WorldMarket
import com.unciv.logic.market.WorldMarketOrders
import com.unciv.models.translations.tr
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.components.input.onClick
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.view.CivView

class WorldMarketSellSection(
    private val playerView: CivView,
    private val refreshParent: () -> Unit
) : Table(BaseScreen.skin) {

    private var selectedResource: String? = null
    private var quantity = 1
    private var message = ""

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
        defaults().pad(4f)

        if (playerView.isSpectator()) return

        val seller = playerView.getCiv()
        add(
            "내 상품 등록".toLabel(
                fontSize = Constants.headingFontSize
            )
        ).left().growX().row()

        add("판매할 자원을 선택하세요.".toLabel())
            .left().growX().row()

        if (message.isNotEmpty()) {
            add(message.toLabel()).left().growX().row()
        }

        val listings = WorldMarket.getResourceListings(
            seller.gameInfo
        ).filter {
            it.seller == seller.civName
        }

        for (listing in listings) {
            val remaining = WorldMarketOrders.availableToList(
                seller, listing.resource
            )

            val line = Table(BaseScreen.skin)

            line.add(
                ImageGetter.getResourcePortrait(
                    listing.resource, 30f
                )
            ).size(30f).padRight(5f)

            line.add(
                "${listing.resource.tr(hideIcons = true)} · 등록 가능 ${remaining}개"
                    .toLabel(hideIcons = true)
            ).growX().left()

            val choose = TextButton(
                if (selectedResource == listing.resource)
                    "선택됨" else "선택",
                BaseScreen.skin
            )
            choose.isDisabled =
                remaining <= 0 || !GUI.isAllowedChangeState()

            choose.onClick {
                selectedResource = listing.resource
                quantity = 1
                refresh()
            }

            line.add(choose)
            add(line).growX().row()
        }

        val chosen = listings.firstOrNull {
            it.resource == selectedResource
        }

        if (chosen != null) {
            val available = WorldMarketOrders.availableToList(
                seller, chosen.resource
            )

            if (available > 0) {
                quantity = quantity.coerceIn(1, available)

                val controls = Table(BaseScreen.skin)

                val minus = TextButton("-", BaseScreen.skin)
                minus.isDisabled = quantity <= 1
                minus.onClick {
                    quantity--
                    refresh()
                }
                controls.add(minus).width(45f)

                controls.add("수량 ${quantity}개".toLabel())
                    .padLeft(10f).padRight(10f)

                val plus = TextButton("+", BaseScreen.skin)
                plus.isDisabled = quantity >= available
                plus.onClick {
                    quantity++
                    refresh()
                }
                controls.add(plus).width(45f)

                val register = TextButton(
                    "판매 등록", BaseScreen.skin
                )
                register.isDisabled =
                    !seller.isHuman() ||
                    !GUI.isAllowedChangeState()

                register.onClick {
                    val order = WorldMarketOrders.register(
                        seller, chosen.resource, quantity
                    )
                    message = if (order != null)
                        "시장에 상품을 등록했습니다."
                    else
                        "등록 실패: 판매 가능 수량을 확인하세요."

                    quantity = 1
                    refresh()
                }

                controls.add(register).padLeft(10f)
                add(controls).left().row()
            }
        }

        val orders = WorldMarketOrders.myOrders(seller)

        add(
            "내 판매 주문 (${orders.size}건)".toLabel(
                fontSize = Constants.headingFontSize
            )
        ).left().padTop(12f).row()

        if (orders.isEmpty()) {
            add("등록한 상품이 없습니다.".toLabel())
                .left().row()
        }

        for (order in orders) {
            val line = Table(BaseScreen.skin)

            line.add(
                "${order.resourceName.tr(hideIcons = true)} · ${order.quantity}개 · 개당 ${order.unitPrice}G"
                    .toLabel(hideIcons = true)
            ).growX().left()

            val cancel = TextButton(
                "등록 취소", BaseScreen.skin
            )
            cancel.isDisabled = !GUI.isAllowedChangeState()

            cancel.onClick {
                message = if (WorldMarketOrders.cancel(
                        seller, order.id
                    ))
                    "판매 주문을 취소했습니다."
                else
                    "주문 취소에 실패했습니다."

                refresh()
            }

            line.add(cancel).padLeft(6f)
            add(line).growX().row()
        }
    }
}
