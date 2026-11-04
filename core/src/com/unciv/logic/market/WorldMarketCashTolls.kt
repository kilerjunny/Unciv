package com.unciv.logic.market

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.tile.Tile

/**
 * GS_WORLD_MARKET_CASH_TOLLS_V1
 * Settles a small third-country transit/port fee only AFTER a valid world-market
 * trade has already transferred its base gold and resource through TradeLogic.
 *
 * No gold creation: seller loses exactly the amount third parties receive.
 * Buyer still pays the listed amount. Supply and route indices are NOT gold.
 * This does not apply to military-market contracts, ordinary diplomacy trades,
 * old recorded trades, or paths which could not be determined.
 */
object WorldMarketCashTolls {
    private const val MaxGoldPerTrade = 6
    private const val MaximumPercent = 8
    private const val FriendlyLandGold = 1
    private const val NeutralLandGold = 2
    // GS_MARITIME_FEES_RATES_V3: standard passage 2G, transshipment 4G.
    private const val ForeignHarborGold = 4
    // GS_MARITIME_PASSAGE_TRANSSHIPMENT_V2: once per third-party controller.
    private const val ForeignSeaPassageGold = 2

    private class Fee(var landGold: Int = 0, var seaGold: Int = 0, var harborGold: Int = 0)

    /** Pure price cap, separate from the much larger pathfinding cost index. */
    fun capForPrice(gold: Int): Int =
        minOf(MaxGoldPerTrade, (gold.toLong().coerceAtLeast(0L) * MaximumPercent / 100L).toInt())

    private fun eligible(owner: Civilization?, seller: Civilization, buyer: Civilization): Boolean =
        owner != null && owner != seller && owner != buyer &&
            !seller.isAtWarWith(owner) && !buyer.isAtWarWith(owner) && !owner.isDefeated()

    private fun isFriendly(seller: Civilization, owner: Civilization): Boolean =
        seller.getDiplomacyManager(owner)?.isConsideredFriendlyTerritory() == true

    /** Identify only unambiguous coastal jurisdiction. Actual water tile ownership
     * wins. For an unowned COAST tile, a single neighboring landowner is a
     * provisional controller; conflicting coastal ownership means FREE passage.
     * Never treat unowned deep ocean as national territory.
     */
    private fun seaController(tile: Tile): Civilization? {
        if (!tile.isWater) return null
        val direct = tile.getOwner()
        if (direct != null) return direct
        if (tile.isOcean) return null
        var controller: Civilization? = null
        for (neighbor in tile.neighbors) {
            if (!neighbor.isLand) continue
            val owner = neighbor.getOwner() ?: continue
            if (controller == null) controller = owner
            else if (controller != owner) return null
        }
        return controller
    }

    /** Charge once per third country for land entry, coastal sea passage and
     * eligible harbor transshipment. Crossing international high seas is free.
     * A country at war with either trading partner is NEVER paid; the routing
     * algorithm separately makes hostile territory expensive but does not yet
     * enforce physical blockades or legal passage permissions.
     */
    internal fun quote(
        seller: Civilization, buyer: Civilization, tiles: List<Tile>, tradeGold: Int
    ): List<MarketTollPayment> {
        val cap = capForPrice(tradeGold)
        if (cap <= 0 || tiles.size < 2) return emptyList()
        // Insertion order follows the actual route, so capped fees are allocated
        // to countries encountered first; independent of hash iteration order.
        val requested = LinkedHashMap<Civilization, Fee>()
        val chargedLand = HashSet<String>()
        val chargedSea = HashSet<String>()
        val chargedPorts = HashSet<String>()

        for (i in 1 until tiles.size) {
            val from = tiles[i - 1]
            val to = tiles[i]
            if (to.isLand) {
                val owner = to.getOwner()
                if (eligible(owner, seller, buyer) && owner != null &&
                    (from.isLand.not() || from.getOwner() != owner) &&
                    chargedLand.add(owner.civID)
                ) {
                    requested.getOrPut(owner) { Fee() }.landGold =
                        if (isFriendly(seller, owner)) FriendlyLandGold else NeutralLandGold
                }
            }
            // Merely passing through a foreign-controlled sea tile is enough;
            // no landing/port call is needed. Charge each country once per trade.
            if (to.isWater) {
                val seaOwner = seaController(to)
                if (eligible(seaOwner, seller, buyer) && seaOwner != null &&
                    chargedSea.add(seaOwner.civID)
                ) {
                    requested.getOrPut(seaOwner) { Fee() }.seaGold = ForeignSeaPassageGold
                }
            }
            if (from.isLand != to.isLand) {
                val harbor = if (from.isLand) from else to
                val portOwner = harbor.getOwner()
                if (harbor.isCityCenter() && eligible(portOwner, seller, buyer) &&
                    portOwner != null && chargedPorts.add(portOwner.civID)
                ) {
                    requested.getOrPut(portOwner) { Fee() }.harborGold = ForeignHarborGold
                }
            }
        }

        // GS_MARITIME_FEES_RATES_V3: allocate scarce capped gold to actual
        // harbor transshipment first, then land passage, then sea passage.
        // This makes a requested 4G port fee meaningful on a 50G contract
        // without violating the existing 8% / 6G cap. Within each category,
        // countries are ordered by first encounter along the physical route.
        var remaining = cap
        val allocated = LinkedHashMap<Civilization, Fee>()
        for (category in 0..2) {
            for ((recipient, request) in requested) {
                if (remaining == 0) break
                val wanted = when (category) {
                    0 -> request.harborGold
                    1 -> request.landGold
                    else -> request.seaGold
                }
                val amount = minOf(remaining, wanted)
                if (amount <= 0) continue
                val payout = allocated.getOrPut(recipient) { Fee() }
                when (category) {
                    0 -> payout.harborGold += amount
                    1 -> payout.landGold += amount
                    else -> payout.seaGold += amount
                }
                remaining -= amount
            }
        }
        return allocated.map { (recipient, fee) ->
            MarketTollPayment(recipient.civID, fee.landGold + fee.seaGold + fee.harborGold,
                fee.landGold, fee.seaGold, fee.harborGold)
        }
    }

    /** Idempotent per MarketTradeRecord. Must only be called for a just-settled
     * world-market purchase, never when loading a save or rendering history.
     */
    fun settle(seller: Civilization, buyer: Civilization, record: MarketTradeRecord) {
        if (record.cashTollSettled) return
        record.cashTollSettled = true
        if (record.estimatedTransportCost < 0 || record.routeTiles.size < 2) return
        val map = seller.gameInfo.tileMap
        val path = ArrayList<Tile>(record.routeTiles.size)
        for (coord in record.routeTiles) {
            val tile = map.getOrNull(coord.x, coord.y) ?: return
            path.add(tile)
        }
        val quoted = quote(seller, buyer, path, record.totalGold)
        if (quoted.isEmpty()) return

        // Prepare and validate all entries BEFORE moving a single gold piece.
        val receivers = ArrayList<Pair<Civilization, Int>>()
        var total = 0
        for (payment in quoted) {
            val recipient = seller.gameInfo.getCivilizationOrNull(payment.civId) ?: return
            if (!eligible(recipient, seller, buyer) || payment.gold <= 0) return
            if (recipient.gold > Int.MAX_VALUE - payment.gold) return
            receivers.add(recipient to payment.gold)
            total += payment.gold
        }
        // Poor/negative seller treasuries are not driven farther into debt.
        // The original sales price has already been credited by TradeLogic.
        if (total <= 0 || seller.gold < total) return

        seller.addGold(-total)
        for ((recipient, amount) in receivers) recipient.addGold(amount)
        record.cashTollGold = total
        record.cashTollPayments.addAll(quoted)
    }
}
