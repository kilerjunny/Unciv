#!/usr/bin/env python3
"""Safely apply the integrated military-market patch to the exact feature/military-market v1.3 base.

Steps: --check (read-only preflight), --apply (write all or rollback), --verify (read-only).
Unlike earlier bundles, unpacking this ZIP alone never modifies game code.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = Path.cwd().resolve()
BRANCH = 'feature/military-market'
BASE_COMMIT_PREFIX = '66a949cf7'
CORE = 'core/src/com/unciv'
FILES = {
    f'{CORE}/logic/GameInfo.kt': '6db290d2d01cdf69db4e6053415e636ca3e725ae',
    f'{CORE}/logic/automation/civilization/NextTurnAutomation.kt': '689d18c4f0931652b32fc414e3bc06210f20cc98',
    f'{CORE}/logic/city/CityConstructions.kt': '33cc29e1e83852c9e7e18896f161cebf68a0b229',
    f'{CORE}/ui/screens/overviewscreen/WorldMarketTradeSection.kt': 'caa2ed4e9b8c6c9489a5b19d32a08cabb7e98f2c',
}


def git(*args: str) -> str:
    r = subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True)
    if r.returncode:
        raise ValueError(f'git {" ".join(args)} failed: {r.stderr.strip()}')
    return r.stdout.strip()


def blob_sha(data: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\x00' + data).hexdigest()


def replace_one(src: str, old: str, new: str, label: str) -> str:
    count = src.count(old)
    if count != 1:
        raise ValueError(f'{label}: expected one exact match, found {count}; no files changed')
    return src.replace(old, new, 1)


def patch_game_info(src: str) -> str:
    src = replace_one(src,
        'import com.unciv.logic.market.MarketSellOrder\n',
        'import com.unciv.logic.market.MarketSellOrder\n'
        'import com.unciv.logic.market.MilitaryMarket\n'
        'import com.unciv.logic.market.MilitaryMarketContract\n'
        'import com.unciv.logic.market.MilitaryMarketHistoryEntry\n',
        'GameInfo imports')
    src = replace_one(src,
        '    var worldMarketHistory = ArrayList<MarketTradeRecord>()\n',
        '    var worldMarketHistory = ArrayList<MarketTradeRecord>()\n'
        '    var militaryMarketContracts = ArrayList<MilitaryMarketContract>()\n'
        '    var militaryMarketHistory = ArrayList<MilitaryMarketHistoryEntry>()\n',
        'GameInfo state')
    src = replace_one(src,
        '        toReturn.worldMarketHistory.addAll(worldMarketHistory.takeLast(100).map { it.copy() })\n',
        '        toReturn.worldMarketHistory.addAll(worldMarketHistory.takeLast(100).map { it.copy() })\n'
        '        toReturn.militaryMarketContracts.addAll(militaryMarketContracts.map { it.copy() })\n'
        '        toReturn.militaryMarketHistory.addAll(militaryMarketHistory.takeLast(100).map { it.copy() })\n',
        'GameInfo copy')
    src = replace_one(src,
        '        GrandStrategyPerf.beginTurn(gameId)\n',
        '        GrandStrategyPerf.beginTurn(gameId)\n'
        '        MilitaryMarket.reconcile(this)\n',
        'GameInfo turn reconcile')
    return src


def patch_city_constructions(src: str) -> str:
    src = replace_one(src,
        'package com.unciv.logic.city\n',
        'package com.unciv.logic.city\n\nimport com.unciv.logic.market.MilitaryMarket\n',
        'CityConstructions import')
    start_token = '    fun completeConstruction(construction: INonPerpetualConstruction): Boolean {'
    end_token = '    fun addBuilding(buildingName: String)'
    start = src.index(start_token)
    end = src.index(end_token, start)
    region = src[start:end]
    region = replace_one(region, '        return true\n    }\n\n',
        '        // Record the REAL unit produced here, before a purchaser can accept delivery.\n'
        '        if (construction is BaseUnit && unit != null) MilitaryMarket.onProduced(city, unit)\n'
        '        return true\n    }\n\n', 'CityConstructions successful completion')
    return src[:start] + region + src[end:]


def patch_unit_ai(src: str) -> str:
    src = replace_one(src,
        'import com.unciv.logic.market.WorldMarketAI\n',
        'import com.unciv.logic.market.WorldMarketAI\n'
        'import com.unciv.logic.market.MilitaryMarket\n',
        'NextTurnAutomation import')
    src = replace_one(src,
        '        fun unitsInPriorityOrder() = unitsAtStart.sortedBy { unit -> getUnitPriority(unit, isAtWar) }\n',
        '        fun unitsInPriorityOrder() = unitsAtStart\n'
        '            .filterNot { MilitaryMarket.isReservedUnit(it) }\n'
        '            .sortedBy { unit -> getUnitPriority(unit, isAtWar) }\n',
        'NextTurnAutomation unit priority')
    src = replace_one(src,
        '            .mapNotNull { it.militaryUnit }.filter { it.civ == civInfo && (!it.baseUnit.isMelee() || it.health > 30) }.toList()\n',
        '            .mapNotNull { it.militaryUnit }.filter { it.civ == civInfo &&\n'
        '                !MilitaryMarket.isReservedUnit(it) &&\n'
        '                (!it.baseUnit.isMelee() || it.health > 30) }.toList()\n',
        'NextTurnAutomation assault')
    src = replace_one(src,
        '                it.civ == civInfo\n                    && it.health >= 100\n',
        '                it.civ == civInfo\n                    && !MilitaryMarket.isReservedUnit(it)\n'
        '                    && it.health >= 100\n',
        'NextTurnAutomation settler escort')
    return src


def patch_market_tab(src: str) -> str:
    src = replace_one(src,
        '    private var performanceOpen = false\n',
        '    private var performanceOpen = false\n    private var militaryMarketOpen = false\n',
        'TradeTab state')
    src = replace_one(src,
        '            if (historyOpen) performanceOpen = false\n',
        '            if (historyOpen) { performanceOpen = false; militaryMarketOpen = false }\n',
        'TradeTab history')
    src = replace_one(src,
        '            if (performanceOpen) historyOpen = false\n',
        '            if (performanceOpen) { historyOpen = false; militaryMarketOpen = false }\n',
        'TradeTab performance')
    src = replace_one(src,
        '        add(perfButton).left().row()\n',
        '        add(perfButton).left().row()\n\n'
        '        val militaryButton = TextButton(\n'
        '            if (militaryMarketOpen) "시장 보기" else "군수 시장", BaseScreen.skin\n'
        '        )\n'
        '        militaryButton.onClick {\n'
        '            militaryMarketOpen = !militaryMarketOpen\n'
        '            if (militaryMarketOpen) { historyOpen = false; performanceOpen = false }\n'
        '            render()\n'
        '            parentTab.overviewScreen.resizePage(parentTab)\n'
        '        }\n'
        '        add(militaryButton).left().row()\n'
        '        if (militaryMarketOpen) {\n'
        '            add(MilitaryMarketSection(playerView) {\n'
        '                parentTab.overviewScreen.resizePage(parentTab)\n'
        '            }).growX().row()\n'
        '            return\n'
        '        }\n',
        'TradeTab military market controls')
    return src

EDITORS = {
    f'{CORE}/logic/GameInfo.kt': patch_game_info,
    f'{CORE}/logic/automation/civilization/NextTurnAutomation.kt': patch_unit_ai,
    f'{CORE}/logic/city/CityConstructions.kt': patch_city_constructions,
    f'{CORE}/ui/screens/overviewscreen/WorldMarketTradeSection.kt': patch_market_tab,
}


def assert_environment() -> None:
    if not (ROOT / '.git').exists() and not git('rev-parse', '--is-inside-work-tree') == 'true':
        raise ValueError('Run from repository root /workspaces/Unciv')
    if not (ROOT / f'{CORE}/logic/GameInfo.kt').is_file():
        raise ValueError('Run from repository root /workspaces/Unciv')
    branch = git('branch', '--show-current')
    if branch != BRANCH:
        raise ValueError(f'Wrong branch {branch!r}; expected {BRANCH!r}. No changes made.')
    commit = git('rev-parse', 'HEAD')
    if subprocess.run(['git', 'merge-base', '--is-ancestor', BASE_COMMIT_PREFIX, 'HEAD'], cwd=ROOT).returncode != 0:
        raise ValueError(f'Unexpected branch HEAD {commit[:12]}; expected {BASE_COMMIT_PREFIX}. No changes made.')


def prepare() -> dict[Path, bytes]:
    assert_environment()
    out: dict[Path, bytes] = {}
    for rel, expected in FILES.items():
        path = ROOT / rel
        source_bytes = path.read_bytes()
        sha = blob_sha(source_bytes)
        if sha != expected:
            raise ValueError(f'Existing source differs from verified v1.3: {rel}\n'
                             f'Expected blob {expected}, found {sha}. Backup/check local edits first.')
        source = source_bytes.decode('utf-8')
        out[path] = EDITORS[rel](source).encode('utf-8')
    for source in sorted((HERE / 'src').rglob('*.kt')):
        rel = source.relative_to(HERE / 'src')
        dest = ROOT / rel
        data = source.read_bytes()
        if dest.exists() and dest.read_bytes() != data:
            raise ValueError(f'Existing extra source conflicts: {rel}. Move it to backup first.')
        out[dest] = data
    if len(out) != 7:
        raise ValueError(f'Unexpected patch file count: {len(out)}')
    return out


def verify() -> None:
    assert_environment()
    for rel, sha in FILES.items():
        path = ROOT / rel
        content = path.read_text(encoding='utf-8')
        # validate all expected integration points, not just file existence
        if rel.endswith('GameInfo.kt'):
            terms = ['militaryMarketContracts', 'militaryMarketHistory', 'MilitaryMarket.reconcile(this)']
        elif rel.endswith('NextTurnAutomation.kt'):
            terms = ['MilitaryMarket.isReservedUnit(it)']
        elif rel.endswith('CityConstructions.kt'):
            terms = ['MilitaryMarket.onProduced(city, unit)']
        else:
            terms = ['MilitaryMarketSection(playerView)']
        for term in terms:
            if term not in content:
                raise ValueError(f'Verification failed: {rel} lacks {term}')
    for file in (HERE / 'src').rglob('*.kt'):
        rel = file.relative_to(HERE / 'src')
        dest = ROOT / rel
        if not dest.is_file() or dest.read_bytes() != file.read_bytes():
            raise ValueError(f'Verification failed: {rel} missing or differs')
    print('VERIFY OK: 4 existing Kotlin files integrated + 3 new Kotlin files present')


def apply(changes: dict[Path, bytes]) -> None:
    # Durable backup exists outside the git repository, even if no local changes were present.
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d_%H%M%S_%f')
    backup = ROOT.parent / 'GS_military_backup' / f'integrated_v12_{stamp}'
    previous: dict[Path, bytes | None] = {p: (p.read_bytes() if p.exists() else None) for p in changes}
    backup.mkdir(parents=True, exist_ok=False)
    for path, data in previous.items():
        if data is not None:
            dst = backup / path.relative_to(ROOT)
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(data)
    print('BACKUP:', backup)
    try:
        for path, data in changes.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        verify()
    except Exception:
        for path, data in previous.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
        raise
    print('APPLY OK: installed all 7 Kotlin files. Next run :core:compileKotlin.')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    choice = parser.add_mutually_exclusive_group(required=True)
    choice.add_argument('--check', action='store_true', help='No changes: checks Git branch/commit, blob SHAs, anchors, file conflicts')
    choice.add_argument('--apply', action='store_true', help='Create backup and apply 4 changes + 3 new files as one transaction')
    choice.add_argument('--verify', action='store_true', help='Check installed integration without modifying source')
    args = parser.parse_args()
    if args.verify:
        verify()
        return
    patch = prepare()
    if args.check:
        print('CHECK OK: exact v1.3/feature branch, four source blobs, all anchors and three new files are compatible.')
        print('Source files are unchanged. Next run this command with --apply.')
        return
    apply(patch)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as e:
        print('PATCH ERROR:', e, file=sys.stderr)
        sys.exit(1)
