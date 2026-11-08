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

    // Per-thread scope: pathfinding may be recursive; count each outermost call once.
    // No instrumentation is added to the save format or to AI decisions.
    private val unitScope = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }
    private val pathDepth = object : ThreadLocal<Int>() {
        override fun initialValue(): Int = 0
    }

    fun <T> measureUnitPhase(block: () -> T): T {
        unitScope.set(unitScope.get() + 1)
        try {
            return measure("units", block)
        } finally {
            unitScope.set(unitScope.get() - 1)
        }
    }

    /** -1 means disabled, 0 means nested, positive means outer path timer. */
    fun startPath(): Long {
        if (unitScope.get() == 0) return -1L
        val nesting = pathDepth.get()
        pathDepth.set(nesting + 1)
        return if (nesting == 0) System.nanoTime() else 0L
    }

    fun finishPath(startedNs: Long) {
        if (startedNs == -1L) return
        pathDepth.set(pathDepth.get() - 1)
        if (startedNs == 0L) return
        val elapsed = System.nanoTime() - startedNs
        synchronized(this) {
            if (turnStart != 0L) {
                durations["unit_pathfinding"] = (durations["unit_pathfinding"] ?: 0L) + elapsed
                counts["unit_pathfinding"] = (counts["unit_pathfinding"] ?: 0) + 1
            }
        }
    }

    @Synchronized
    fun addCount(name: String, amount: Int) {
        if (turnStart != 0L) counts[name] = (counts[name] ?: 0) + amount
    }

    @Synchronized
    fun setCount(name: String, amount: Int) {
        if (turnStart != 0L) counts[name] = amount
    }

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
        // GS_ECONOMIC_TRADE_ROUTES_V1: nested in market activity; do not add to totals.
        lines.add("경제 경로 탐색: ${ms(result.durations["market_economic_route"] ?: 0L)} ms " +
            "(${result.counts["market_economic_route"] ?: 0}회, 시장 처리와 중복)")

        // Top-level categories only. Subtimers intentionally overlap their parents.
        val direct = groups.sumOf { (_, key) -> result.durations[key] ?: 0L }
        val other = (result.totalNs - direct).coerceAtLeast(0L)

        fun item(label: String, key: String) {
            lines.add("$label: ${ms(result.durations[key] ?: 0L)} ms (${result.counts[key] ?: 0}회)")
        }
        lines.add("---- 유닛 AI 상세 (상위 항목과 중복 집계) ----")
        lines.add("처리 대상 유닛: ${result.counts["unit_seen"] ?: 0}기 / 군사 유닛: ${result.counts["unit_military_seen"] ?: 0}기")
        lines.add("전쟁 중 주요 문명: ${result.counts["war_civs"] ?: 0}개 / 활동 AI 문명: ${result.counts["ai_civs"] ?: 0}개")
        item("승급 처리", "unit_promotions")
        item("특수 유닛 이동", "unit_special_moves")
        // GS_AI_PERF_SAFE_V1: only counters, not an additional timed phase.
        lines.add("특수 이동 우선순위 정렬 대상: ${result.counts["unit_special_candidates"] ?: 0}기")
        lines.add("호위 대상 없음: 이동 경로 탐색 ${result.counts["unit_escort_paths_skipped"] ?: 0}회 생략")
        item("호위 판단", "unit_escort")
        item("적 도시 점령 판단", "unit_city_assault")
        item("일반 유닛 이동", "unit_regular_moves")
        item("일반 이동 - 민간", "unit_civilian")
        item("일반 이동 - 항공/핵", "unit_air")
        item("일반 이동 - 지상/해상", "unit_military")
        item("경로 탐색 (중첩 제외)", "unit_pathfinding")
        // GS_UNIT_PATH_CACHE_V1: Diagnostics only; no extra full path searches.
        lines.add("최단경로 캐시 적중: ${result.counts["unit_path_cache_hits"] ?: 0}회 (판단당 최대 4개 경로)")
        item("근접 공격 판단", "unit_attack_scan")
        item("적 도시 목표 탐색", "unit_city_target")
        item("적 접근 판단", "unit_attack_advance")
        // GS_STRATEGIC_DEPLOYMENT_AI_V1: included within regular unit AI time.
        item("평시 전략 배치 판단", "unit_strategic_deployment")
        lines.add("전략 배치 계획 갱신: ${result.counts["strategic_deploy_replans"] ?: 0}회 / " +
            "배정 유닛: ${result.counts["strategic_deploy_assigned"] ?: 0}기")
        lines.add("전략 주둔 유지: ${result.counts["strategic_deploy_holding"] ?: 0}회 / " +
            "전략 재배치 이동: ${result.counts["strategic_deploy_moves"] ?: 0}회 / " +
            "경로 불가: ${result.counts["strategic_deploy_unreachable"] ?: 0}회")
        // GS_STRATEGIC_DEPLOYMENT_AI_V2: already included in unit AI timing.
        lines.add("해군 귀항/거점 배정: ${result.counts["strategic_naval_assigned"] ?: 0}기 (계획 갱신 누적)")
        lines.add("해군 주둔 유지: ${result.counts["strategic_naval_holding"] ?: 0}회 / " +
            "귀항·재배치 이동: ${result.counts["strategic_naval_moves"] ?: 0}회 / " +
            "항구 경로 실패: ${result.counts["strategic_naval_port_failures"] ?: 0}회")
        // GS_AI_PATH_PRECHECK_V2: counts are per checked military AI decision,
        // not a separate duration. Do not sum with pathfinding time.
        lines.add("근접 적 없음: 이동범위 경로 탐색 ${result.counts["unit_advance_paths_skipped"] ?: 0}회 생략")

        val aiTotal = result.durations["phase_ai_total"] ?: 0L
        val start = result.durations["phase_turn_start"] ?: 0L
        val end = result.durations["phase_turn_end"] ?: 0L
        val aiOther = (aiTotal - direct).coerceAtLeast(0L)
        val remaining = (result.totalNs - aiTotal - start - end).coerceAtLeast(0L)
        lines.add("---- 기타 턴 처리 상세 ----")
        lines.add("기타 턴 처리: ${ms(other)} ms")
        lines.add("AI 자동화 기타: ${ms(aiOther)} ms")
        lines.add("턴 시작 갱신: ${ms(start)} ms")
        lines.add("턴 종료 갱신: ${ms(end)} ms")
        // GS_TURN_PHASE_PROFILE_V3: all values below are INCLUDED in the phase totals above.
        val startDetails = listOf(
            "편제 유닛 정리" to "turn_start_formations",
            "연구 진행" to "turn_start_research",
            "자원·비축 갱신" to "turn_start_resources",
            "시장 주문 조정" to "turn_start_market_orders",
            "건설 처리" to "turn_start_constructions",
            "문명 턴 통계" to "turn_start_stats",
            "종교 시작" to "turn_start_religion",
            "시야·탐색 갱신" to "turn_start_vision",
            "수도 연결 도시" to "turn_start_city_connections",
            "국가 상태·반란" to "turn_start_flags_revolts",
            "도시 시작 갱신" to "turn_start_cities",
            "유닛 시작 갱신" to "turn_start_units"
        )
        val endDetails = listOf(
            "도시 자동 포격" to "turn_end_bombard",
            "종료 트리거" to "turn_end_triggers",
            "문명 통계 재계산" to "turn_end_stats",
            "정책 처리" to "turn_end_policies",
            "금 수입 정산" to "turn_end_income",
            "연구 종료" to "turn_end_research",
            "종교 종료" to "turn_end_religion",
            "첩보 종료" to "turn_end_espionage",
            "도시 종료 갱신" to "turn_end_cities",
            "임시 효과·행복" to "turn_end_temporary_happiness",
            "유닛 종료 갱신" to "turn_end_units",
            "외교 관계 갱신" to "turn_end_diplomacy"
        )
        lines.add("---- 턴 시작 상세 (턴 시작 갱신에 포함) ----")
        for ((label, key) in startDetails) item(label, key)
        val measuredStart = startDetails.sumOf { (_, key) -> result.durations[key] ?: 0L }
        lines.add("시작 기타·미계측: ${ms((start - measuredStart).coerceAtLeast(0L))} ms")
        lines.add("---- 턴 종료 상세 (턴 종료 갱신에 포함) ----")
        for ((label, key) in endDetails) item(label, key)
        val measuredEnd = endDetails.sumOf { (_, key) -> result.durations[key] ?: 0L }
        lines.add("종료 기타·미계측: ${ms((end - measuredEnd).coerceAtLeast(0L))} ms")
        lines.add("※ 상세 계측은 소량의 오버헤드를 포함합니다. 상위 시간과 합산하지 마세요.")
        lines.add("나머지/관리 작업: ${ms(remaining)} ms")
        lines.add("※ 경로/공격 판단은 유닛 AI 시간에 포함되므로 합산하지 마세요.")
        lines.add("단위: ms (1,000ms = 1초)")
        lines.add("※ 앱 화면 렌더링 시간은 포함하지 않음")
        return lines
    }
}
