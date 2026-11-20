package com.unciv.logic.market

import com.unciv.logic.GameInfo

/**
 * GS_CITY_TRADE_STATS_V1
 * Observation-only city statistics based on the game's retained world-market
 * settlements. Does NOT alter trades, gold, routes, or saved-game fields.
 *
 * IMPORTANT: game.worldMarketHistory retains at most 100 records. These
 * statistics are NOT an all-time income ledger. Some old/unrouted records
 * have no city name; they are reported as unattributed rather than guessed.
 */
object WorldMarketCityStatistics {
    data class CityTotals(
        val civId: String,
        val cityName: String,
        var exportTrades: Int = 0,
        var importTrades: Int = 0,
        var exportedUnits: Int = 0,
        var importedUnits: Int = 0,
        var grossSalesGold: Long = 0L,
        var actualTollsPaidGold: Long = 0L,
        var importSpendingGold: Long = 0L,
        var capitalFallbackTrades: Int = 0
    ) {
        /** Cash the exporter actually retained, not profit after production. */
        val netSalesGold: Long get() = grossSalesGold - actualTollsPaidGold
        /** Direct sales receipts minus purchases; excludes third-party toll income. */
        val tradeBalanceGold: Long get() = netSalesGold - importSpendingGold
    }

    data class Report(
        val cities: List<CityTotals>,
        val tradesInScope: Int,
        val exportWithoutCity: Int,
        val importWithoutCity: Int,
        val maxHistoryRecords: Int = 100
    )

    /**
     * @param civId null = all countries, otherwise only this country's cities.
     * @param recentTurns null = all retained 100 trades; otherwise additionally
     * restrict to the latest [recentTurns] turns of the retained records.
     */
    fun calculate(game: GameInfo, civId: String? = null, recentTurns: Int? = null): Report {
        val minTurn = if (recentTurns == null) Int.MIN_VALUE
            else (game.turns.toLong() - recentTurns.coerceAtLeast(1).toLong() + 1L)
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
        val byCity = LinkedHashMap<Pair<String, String>, CityTotals>()
        var trades = 0
        var missingExports = 0
        var missingImports = 0

        fun city(owner: String, name: String): CityTotals = byCity.getOrPut(owner to name) {
            CityTotals(owner, name)
        }

        for (trade in game.worldMarketHistory) {
            if (trade.turn < minTurn || trade.quantity <= 0 || trade.totalGold <= 0) continue
            val sellerIncluded = civId == null || civId == trade.sellerCivId
            val buyerIncluded = civId == null || civId == trade.buyerCivId
            if (!sellerIncluded && !buyerIncluded) continue
            trades++
            val cashGold = trade.totalGold.toLong()
            val actualToll = trade.cashTollGold.coerceIn(0, trade.totalGold).toLong()

            if (sellerIncluded) {
                if (trade.originCityName.isBlank()) missingExports++
                else {
                    val totals = city(trade.sellerCivId, trade.originCityName)
                    totals.exportTrades++
                    totals.exportedUnits += trade.quantity
                    totals.grossSalesGold += cashGold
                    totals.actualTollsPaidGold += actualToll
                    if (trade.originKind == "CAPITAL_FALLBACK") totals.capitalFallbackTrades++
                }
            }
            if (buyerIncluded) {
                if (trade.destinationCityName.isBlank()) missingImports++
                else {
                    val totals = city(trade.buyerCivId, trade.destinationCityName)
                    totals.importTrades++
                    totals.importedUnits += trade.quantity
                    totals.importSpendingGold += cashGold
                }
            }
        }
        return Report(byCity.values.toList(), trades, missingExports, missingImports)
    }
}
