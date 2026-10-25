package com.unciv.logic.automation.civilization

/**
 * Diagnostic only. Keeps the latest completed turn in memory.
 * No save-game data is added.
 */
object GrandStrategyPerf {
    private data class Snapshot(
        val gameId: String,
        val turn: Int,
        val totalNs: Long,
        val durations: Map<String, Long>,
        val counts: Map<String, Int>
    )

    private var activeGame = ""
    private var turnStart = 0L
    private val durations = HashMap<String, Long>()
    private val counts = HashMap<String, Int>()
    private var last: Snapshot? = null

    @Synchronized
    fun beginTurn(gameId: String) {
        activeGame = gameId
        turnStart = System.nanoTime()
        durations.clear()
        counts.clear()
    }

    fun <T> measure(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            val elapsed = System.nanoTime() - start
            synchronized(this) {
                if (turnStart != 0L) {
                    durations[name] =
                        (durations[name] ?: 0L) + elapsed
                    counts[name] =
                        (counts[name] ?: 0) + 1
                }
            }
        }
    }

    @Synchronized
    fun finishTurn(gameId: String, turn: Int) {
        if (turnStart == 0L || activeGame != gameId) return

        val total = System.nanoTime() - turnStart
        last = Snapshot(
            gameId, turn, total,
            HashMap(durations),
            HashMap(counts)
        )
        turnStart = 0L
    }

    private fun ms(nanos: Long): String {
        val tenths = nanos.coerceAtLeast(0L) / 100_000L
        return "${tenths / 10}.${tenths % 10}"
    }

    @Synchronized
    fun reportLines(gameId: String): List<String> {
        val result = last
        if (result == null || result.gameId != gameId)
            return listOf(
                "측정 데이터 없음",
                "턴을 1회 진행한 뒤 다시 열어주세요."
            )

        val groups = listOf(
            "전략 AI" to "strategy",
            "시장 자동 판매" to "market_supply",
            "시장 자동 구매" to "market_purchase",
            "유닛 AI" to "units",
            "도시 AI" to "cities"
        )

        val lines = ArrayList<String>()
        lines.add("Grand Strategy 성능 진단")
        lines.add("측정 완료: ${result.turn}턴")
        lines.add("전체 턴 처리: ${ms(result.totalNs)} ms")

        for ((label, key) in groups) {
            val ns = result.durations[key] ?: 0L
            val count = result.counts[key] ?: 0
            lines.add("$label: ${ms(ns)} ms (${count}회)")
        }

        val measured = result.durations.values.sum()
        val other = (result.totalNs - measured)
            .coerceAtLeast(0L)

        lines.add("기타 턴 처리: ${ms(other)} ms")
        lines.add("단위: ms (1,000ms = 1초)")
        lines.add("※ 앱 화면 렌더링 시간은 포함하지 않음")
        return lines
    }
}
