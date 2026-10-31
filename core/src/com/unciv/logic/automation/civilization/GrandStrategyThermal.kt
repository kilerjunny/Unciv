package com.unciv.logic.automation.civilization

/**
 * GS_THERMAL_MONITOR_V1
 * In-memory, low-frequency Android thermal readings. No save data or game rules affected.
 * Battery temperature is NOT CPU/GPU/skin temperature, and the Android thermal level
 * is a throttling severity signal rather than a temperature in degrees.
 */
object GrandStrategyThermal {
    private var samples = 0
    private var startedAtMs = 0L
    private var latestAtMs = 0L
    private var firstTemp: Float? = null
    private var latestTemp: Float? = null
    private var lowestTemp: Float? = null
    private var highestTemp: Float? = null
    private var currentStatus: Int? = null
    private var worstStatus: Int? = null
    private var charging: Boolean? = null

    @Synchronized
    fun record(batteryCelsius: Float?, thermalStatus: Int?, isCharging: Boolean?) {
        val now = System.currentTimeMillis()
        if (samples == 0) startedAtMs = now
        samples++
        latestAtMs = now
        batteryCelsius?.let { temperature ->
            if (temperature in -20f..100f) {
                if (firstTemp == null) firstTemp = temperature
                latestTemp = temperature
                lowestTemp = minOf(lowestTemp ?: temperature, temperature)
                highestTemp = maxOf(highestTemp ?: temperature, temperature)
            }
        }
        currentStatus = thermalStatus
        if (thermalStatus != null) worstStatus = maxOf(worstStatus ?: thermalStatus, thermalStatus)
        charging = isCharging
    }

    private fun temp(value: Float?): String =
        if (value == null) "확인 불가" else "${"%.1f".format(java.util.Locale.US, value)}°C"

    private fun status(value: Int?): String = when (value) {
        null -> "확인 불가"
        0 -> "없음(제한 없음)"
        1 -> "경미(LIGHT)"
        2 -> "중간(MODERATE)"
        3 -> "심함(SEVERE)"
        4 -> "위험(CRITICAL)"
        5 -> "긴급(EMERGENCY)"
        6 -> "종료 필요(SHUTDOWN)"
        else -> "알 수 없음($value)"
    }

    @Synchronized
    fun reportLines(): List<String> {
        if (samples == 0) return listOf(
            "---- Android 발열 측정 ----",
            "측정 데이터 없음 (Android 게임에서 실행 후 확인)",
            "배터리 온도는 CPU/GPU 온도가 아닙니다."
        )
        val elapsed = ((latestAtMs - startedAtMs) / 1000L).coerceAtLeast(0L)
        return listOf(
            "---- Android 발열 측정 (앱 실행 중 15초 간격) ----",
            "배터리 온도: 현재 ${temp(latestTemp)} · 시작 ${temp(firstTemp)}",
            "배터리 온도: 최저 ${temp(lowestTemp)} · 최고 ${temp(highestTemp)}",
            "Android 발열 제한: 현재 ${status(currentStatus)} · 최악 ${status(worstStatus)}",
            "충전 상태: ${when (charging) { true -> "충전 중"; false -> "충전 아님"; null -> "확인 불가" }}",
            "측정: ${samples}회 · 기록 구간 ${elapsed / 60}분 ${elapsed % 60}초",
            "※ 배터리 온도는 CPU/GPU 온도가 아닙니다. 기기별 센서 제공 여부에 따라 확인 불가할 수 있습니다.",
            "※ 최근 샘플은 화면 열기/새로고침 시 표시되며, 앱을 완전히 종료하면 기록은 초기화됩니다."
        )
    }
}
