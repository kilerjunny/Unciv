package com.unciv.logic.market

import com.unciv.logic.GameInfo
import kotlin.math.roundToInt

/**
 * Observation-only trade flow ledger. No changes to gold/resources or order matching.
 *
 * Window traffic is aggregated per route AND turn; lifetime traffic is aggregated per route.
 * Both lists are bounded to protect Android memory and save size. Historic saves can be
 * bootstrapped from the last 100 market trades, which is only a partial historical record.
 */
object WorldMarketRouteStats {
    private const val ReferenceTurns = 30
    private const val MaxWindowRows = 1600
    private const val MaxLifetimeRoutes = 400

    fun windowTurns(game: GameInfo): Int =
        (ReferenceTurns * game.speed.modifier).roundToInt().coerceIn(5, 360)

    fun initializeIfNeeded(game: GameInfo) {
        if (game.worldMarketStatsInitialized) return
        game.worldMarketStatsInitialized = true
        // Prior saves had only the 100 most recent individual transactions.
        for (record in game.worldMarketHistory) append(game, record)
    }

    fun record(game: GameInfo, trade: MarketTradeRecord) {
        initializeIfNeeded(game)
        append(game, trade)
    }

    fun windowRows(game: GameInfo): List<MarketRouteStat> {
        initializeIfNeeded(game)
        prune(game)
        return game.worldMarketRouteStats
    }

    fun lifetimeRows(game: GameInfo): List<MarketRouteStat> {
        initializeIfNeeded(game)
        return game.worldMarketRouteLifetime
    }

    private fun append(game: GameInfo, trade: MarketTradeRecord) {
        if (trade.routeTiles.size < 2 || trade.routeMode !in setOf("LAND", "SEA", "MIXED")) return
        if (trade.quantity <= 0 || trade.totalGold <= 0) return
        prune(game)
        val hash = trade.routeTiles.hashCode()
        val window = game.worldMarketRouteStats
        // Historical records older than the selected speed-scaled window don't enter the window ledger.
        if (trade.turn >= game.turns - windowTurns(game) + 1) {
            // Records arrive in turn order, so scan only the current turn's entries.
            val existing = window.asReversed()
                .takeWhile { it.turn == trade.turn }
                .firstOrNull { matches(it, trade, hash) }
            if (existing == null) {
                window.add(newRow(trade, hash))
            } else {
                existing.tradeCount++
                existing.quantity += trade.quantity
                existing.totalGold += trade.totalGold
            }
            while (window.size > MaxWindowRows) window.removeAt(0)
        }

        val lifetime = game.worldMarketRouteLifetime
        val existingLifetime = lifetime.firstOrNull { matches(it, trade, hash) }
        if (existingLifetime != null) {
            existingLifetime.turn = trade.turn
            existingLifetime.tradeCount++
            existingLifetime.quantity += trade.quantity
            existingLifetime.totalGold += trade.totalGold
        } else if (lifetime.size < MaxLifetimeRoutes) {
            lifetime.add(newRow(trade, hash))
        } else {
            // Keep the most recently active routes, and warn that lifetime coverage is partial.
            game.worldMarketRouteLifetimeTruncated = true
            val oldest = lifetime.indices.minByOrNull { lifetime[it].turn } ?: 0
            lifetime.removeAt(oldest)
            lifetime.add(newRow(trade, hash))
        }
    }

    private fun matches(row: MarketRouteStat, trade: MarketTradeRecord, hash: Int) =
        row.sellerCivId == trade.sellerCivId &&
            row.buyerCivId == trade.buyerCivId &&
            row.routeMode == trade.routeMode &&
            row.originCityName == trade.originCityName &&
            row.destinationCityName == trade.destinationCityName &&
            row.routeHash == hash && row.routeTiles == trade.routeTiles

    private fun newRow(trade: MarketTradeRecord, hash: Int) = MarketRouteStat(
        trade.turn, trade.sellerCivId, trade.buyerCivId, trade.routeMode,
        trade.originCityName, trade.destinationCityName, hash, ArrayList(trade.routeTiles),
        1, trade.quantity, trade.totalGold
    )

    private fun prune(game: GameInfo) {
        val minTurn = game.turns - windowTurns(game) + 1
        game.worldMarketRouteStats.removeAll { it.turn < minTurn }
    }
}
