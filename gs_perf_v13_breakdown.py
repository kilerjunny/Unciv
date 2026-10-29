#!/usr/bin/env python3
"""Unciv Grand Strategy profiler v1.3: read-only in-memory metrics.

Run from the root of kilerjunny/Unciv on an isolated branch based on
perf/profiler-v12. Usage: --check or --apply.
"""
from __future__ import annotations
import argparse
import subprocess
from pathlib import Path

P = Path('core/src/com/unciv')
PERF = P/'logic/automation/civilization/GrandStrategyPerf.kt'
NEXT = P/'logic/automation/civilization/NextTurnAutomation.kt'
UNIT = P/'logic/automation/unit/UnitAutomation.kt'
MOVE = P/'logic/map/mapunit/movement/UnitMovement.kt'
GAME = P/'logic/GameInfo.kt'
FILES = [PERF, NEXT, UNIT, MOVE, GAME]


def exactly(s: str, old: str, new: str, label: str, count: int = 1) -> str:
    found = s.count(old)
    if found != count:
        raise RuntimeError(f'{label}: expected {count} matching anchor(s), found {found}')
    return s.replace(old, new)


def bracket_end(s: str, opening: int) -> int:
    """Locate a Kotlin brace closing a known function body, skipping comments/strings."""
    assert s[opening] == '{'
    depth, i, n = 0, opening, len(s)
    state = 'code'
    while i < n:
        ch = s[i]
        two = s[i:i+2]
        if state == 'line':
            if ch == '\n': state = 'code'
        elif state == 'block':
            if two == '*/': state = 'code'; i += 2; continue
        elif state == 'str':
            if ch == '\\': i += 2; continue
            if ch == '"': state = 'code'
        elif state == 'triple':
            if s[i:i+3] == '"""': state = 'code'; i += 3; continue
        elif state == 'char':
            if ch == '\\': i += 2; continue
            if ch == "'": state = 'code'
        else:
            if two == '//': state = 'line'; i += 2; continue
            if two == '/*': state = 'block'; i += 2; continue
            if s[i:i+3] == '"""': state = 'triple'; i += 3; continue
            if ch == '"': state = 'str'
            elif ch == "'": state = 'char'
            elif ch == '{': depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0: return i
        i += 1
    raise RuntimeError('unbalanced Kotlin braces')


def wrap_path_function(s: str, name: str) -> str:
    sig = f'    fun {name}('
    if s.count(sig) != 1: raise RuntimeError(f'{name}: missing or ambiguous function')
    i = s.index(sig)
    # The targeted functions have no parameter lambdas or braces preceding their body.
    opening = s.index('{', i)
    end = bracket_end(s, opening)
    # Insert end first to preserve existing offsets.
    s = s[:end] + (
        '        } finally {\n'
        '            GrandStrategyPerf.finishPath(gsPathStart)\n'
        '        }\n'
        '    '
    ) + s[end:]
    s = s[:opening+1] + (
        '\n        val gsPathStart = GrandStrategyPerf.startPath()\n'
        '        try {'
    ) + s[opening+1:]
    return s


def patch_perf(s: str) -> str:
    s = exactly(s,
        '    private var last: Snapshot? = null\n',
        '''    private var last: Snapshot? = null

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
''', 'perf fields')

    s = exactly(s,
'''        val measured = result.durations.values.sum()
        val other = (result.totalNs - measured)
            .coerceAtLeast(0L)

        lines.add("기타 턴 처리: ${ms(other)} ms")
        lines.add("단위: ms (1,000ms = 1초)")
''',
'''        // Top-level categories only. Subtimers intentionally overlap their parents.
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
        item("호위 판단", "unit_escort")
        item("적 도시 점령 판단", "unit_city_assault")
        item("일반 유닛 이동", "unit_regular_moves")
        item("일반 이동 - 민간", "unit_civilian")
        item("일반 이동 - 항공/핵", "unit_air")
        item("일반 이동 - 지상/해상", "unit_military")
        item("경로 탐색 (중첩 제외)", "unit_pathfinding")
        item("근접 공격 판단", "unit_attack_scan")
        item("적 도시 목표 탐색", "unit_city_target")
        item("적 접근 판단", "unit_attack_advance")

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
        lines.add("나머지/관리 작업: ${ms(remaining)} ms")
        lines.add("※ 경로/공격 판단은 유닛 AI 시간에 포함되므로 합산하지 마세요.")
        lines.add("단위: ms (1,000ms = 1초)")
''', 'perf report')
    return s


def patch_next(s: str) -> str:
    s = exactly(s,
'''        GrandStrategyPerf.measure("units") {
            automateUnits(civInfo)
        }''',
'''        GrandStrategyPerf.measureUnitPhase {
            automateUnits(civInfo)
        }''', 'next unit phase')
    s = exactly(s,
'''        val unitsAtStart = civInfo.units.getCivUnits().toList()
        fun unitsInPriorityOrder()''',
'''        val unitsAtStart = civInfo.units.getCivUnits().toList()
        GrandStrategyPerf.addCount("unit_seen", unitsAtStart.size)
        GrandStrategyPerf.addCount("unit_military_seen", unitsAtStart.count { it.isMilitary() })
        fun unitsInPriorityOrder()''', 'unit counts')
    s = exactly(s,
'''        for (unit in unitsInPriorityOrder()) {
            if (!isStillOurUnit(unit, civInfo)) continue
            applyPromotions(unit)
        }
''',
'''        GrandStrategyPerf.measure("unit_promotions") {
            for (unit in unitsInPriorityOrder()) {
                if (!isStillOurUnit(unit, civInfo)) continue
                applyPromotions(unit)
            }
        }
''', 'promotions')
    s = exactly(s,
'''        for (unit in unitsInPriorityOrder()) {
            // settlers need to move before automateSettlerEscorting(),
            // move spaceship parts before that to make sure we're not blocking them
            if (!unit.hasUnique(UniqueType.SpaceshipPart) && !unit.hasUnique(UniqueType.FoundCity)) continue
            if (!isStillOurUnit(unit, civInfo)) continue
            UnitAutomation.automateUnitMoves(unit)
        }
''',
'''        GrandStrategyPerf.measure("unit_special_moves") {
            for (unit in unitsInPriorityOrder()) {
                // settlers need to move before automateSettlerEscorting(),
                // move spaceship parts before that to make sure we're not blocking them
                if (!unit.hasUnique(UniqueType.SpaceshipPart) && !unit.hasUnique(UniqueType.FoundCity)) continue
                if (!isStillOurUnit(unit, civInfo)) continue
                UnitAutomation.automateUnitMoves(unit)
            }
        }
''', 'special unit movement')
    s = exactly(s,
'''        if (civInfo.cities.isNotEmpty()) automateSettlerEscorting(civInfo)

        for (city in citiesRequiringManualPlacement) automateCityConquer(civInfo, city)

        for (unit in unitsInPriorityOrder()) {
            // spaceship parts and settlers have already moved
            if (unit.hasUnique(UniqueType.SpaceshipPart) || unit.hasUnique(UniqueType.FoundCity)) continue
            if (!isStillOurUnit(unit, civInfo)) continue
            UnitAutomation.automateUnitMoves(unit)
        }
''',
'''        GrandStrategyPerf.measure("unit_escort") {
            if (civInfo.cities.isNotEmpty()) automateSettlerEscorting(civInfo)
        }

        GrandStrategyPerf.measure("unit_city_assault") {
            for (city in citiesRequiringManualPlacement) automateCityConquer(civInfo, city)
        }

        GrandStrategyPerf.measure("unit_regular_moves") {
            for (unit in unitsInPriorityOrder()) {
                // spaceship parts and settlers have already moved
                if (unit.hasUnique(UniqueType.SpaceshipPart) || unit.hasUnique(UniqueType.FoundCity)) continue
                if (!isStillOurUnit(unit, civInfo)) continue
                val category = when {
                    unit.isCivilian() -> "unit_civilian"
                    unit.isNuclearWeapon() || unit.baseUnit.isAirUnit() -> "unit_air"
                    else -> "unit_military"
                }
                GrandStrategyPerf.measure(category) { UnitAutomation.automateUnitMoves(unit) }
            }
        }
''', 'regular unit movement')
    return s


def patch_unit(s: str) -> str:
    s = exactly(s,
        'import com.unciv.logic.automation.civilization.NextTurnAutomation\n',
        'import com.unciv.logic.automation.civilization.NextTurnAutomation\nimport com.unciv.logic.automation.civilization.GrandStrategyPerf\n',
        'unit import')
    s = exactly(s,
        '        if (tryAttacking(unit)) return',
        '        if (GrandStrategyPerf.measure("unit_attack_scan") { tryAttacking(unit) }) return',
        'attack assessment')
    s = exactly(s,
        '        if (HeadTowardsEnemyCityAutomation.tryHeadTowardsEnemyCity(unit)) return',
        '        if (GrandStrategyPerf.measure("unit_city_target") { HeadTowardsEnemyCityAutomation.tryHeadTowardsEnemyCity(unit) }) return',
        'city target')
    s = exactly(s,
        '        if (tryAdvanceTowardsCloseEnemy(unit)) return',
        '        if (GrandStrategyPerf.measure("unit_attack_advance") { tryAdvanceTowardsCloseEnemy(unit) }) return',
        'advance')
    return s


def patch_movement(s: str) -> str:
    s = exactly(s,
        'import com.unciv.logic.automation.Timers.Companion.timeThis\n',
        'import com.unciv.logic.automation.Timers.Companion.timeThis\nimport com.unciv.logic.automation.civilization.GrandStrategyPerf\n',
        'movement import')
    for fn in ('getMovementToTilesAtPosition', 'getShortestPath', 'getDistanceToTiles'):
        s = wrap_path_function(s, fn)
    return s


def patch_game(s: str) -> str:
    s = exactly(s,
        'TurnManager(player).startTurn(progressBar)',
        'GrandStrategyPerf.measure("phase_turn_start") { TurnManager(player).startTurn(progressBar) }',
        'startTurn', 2)
    s = exactly(s,
        'TurnManager(player).endTurn(progressBar)',
        'GrandStrategyPerf.measure("phase_turn_end") { TurnManager(player).endTurn(progressBar) }',
        'endTurn', 2)
    s = exactly(s,
        'TurnManager(player).automateTurn()',
        'GrandStrategyPerf.measure("phase_ai_total") { TurnManager(player).automateTurn() }',
        'automateTurn')
    s = exactly(s,
        '        GrandStrategyPerf.finishTurn(gameId, turns)\n',
        '''        GrandStrategyPerf.setCount("war_civs", civilizations.count { it.isMajorCiv() && it.isAlive() && it.isAtWar() })
        GrandStrategyPerf.setCount("ai_civs", civilizations.count { it.isAI() && it.isAlive() })
        GrandStrategyPerf.finishTurn(gameId, turns)
''', 'game counters')
    return s

PATCHERS = {PERF: patch_perf, NEXT: patch_next, UNIT: patch_unit,
            MOVE: patch_movement, GAME: patch_game}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--check', action='store_true', help='validate anchors, no file changes')
    group.add_argument('--apply', action='store_true', help='apply after validation')
    args = parser.parse_args()
    branch = subprocess.run(['git', 'branch', '--show-current'], capture_output=True, text=True)
    if branch.returncode:
        raise RuntimeError('Please run from a Git checkout')
    if args.apply and branch.stdout.strip() != 'perf/profiler-v13-breakdown':
        raise RuntimeError('Safety stop: switch to perf/profiler-v13-breakdown first')

    changes: dict[Path, str] = {}
    for path, patcher in PATCHERS.items():
        if not path.is_file(): raise RuntimeError(f'Missing file {path}; are you in Unciv repo root?')
        old = path.read_text(encoding='utf-8')
        new = patcher(old)
        if old == new: raise RuntimeError(f'No changes generated for {path}')
        changes[path] = new
        print(f'OK {path}: {len(old)} -> {len(new)} chars')
    print('All 5 files validated on branch', branch.stdout.strip())
    if args.check: return
    for path, new in changes.items():
        path.write_text(new, encoding='utf-8')
    print('Patch applied. Next: ./gradlew :core:compileKotlin --no-daemon')


if __name__ == '__main__':
    main()
