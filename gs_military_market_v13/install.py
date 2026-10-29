#!/usr/bin/env python3
"""Safe v1.3 upgrade on feature/military-market-v13 (v1.2 base).
--check reads only; --apply backs up and changes 7 Kotlin files or rolls back; --verify reads only.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import shutil
import subprocess
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = Path.cwd().resolve()
BRANCH = 'feature/military-market-v13'
BASE_COMMIT_PREFIX = '8f48c9f0c'
CORE = 'core/src/com/unciv'
PATHS = {
    f'{CORE}/logic/GameInfo.kt': 'dd52098aeae8ba0b65ead18a754650fcf7ed2d34',
    f'{CORE}/logic/automation/civilization/NextTurnAutomation.kt': '3b786c6f3d2a29a5a648809a7f1e939586582d83',
    f'{CORE}/ui/screens/overviewscreen/EmpireOverviewScreen.kt': 'c33337e0fbd4dcfd00df3dfea19f129b0a7a96d4',
    f'{CORE}/logic/market/MilitaryMarket.kt': '481918e06d03589b30fb3b51ff8b426b8cfb5fc0',
    f'{CORE}/ui/screens/overviewscreen/MilitaryMarketSection.kt': '7af0fe98b0d53978ab894520589ab4cb7a8e2e1e',
}
NEW = {
    f'{CORE}/logic/market/MilitaryMarketAI.kt',
    f'{CORE}/logic/market/MilitaryMarketListing.kt',
}
ALL = set(PATHS) | NEW


def git(*args: str, checked=True):
    r = subprocess.run(['git', *args], cwd=ROOT, capture_output=True, text=True)
    if checked and r.returncode:
        raise ValueError(f'git {" ".join(args)}: {r.stderr.strip()}')
    return r


def sha(data: bytes) -> str:
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\x00' + data).hexdigest()


def replace_one(src: str, old: str, new: str, desc: str) -> str:
    count = src.count(old)
    if count != 1:
        raise ValueError(f'{desc}: expected 1 anchor, found {count}; no changes made')
    return src.replace(old, new, 1)


def patch_gameinfo(src: str) -> str:
    src = replace_one(src,
        'import com.unciv.logic.market.MilitaryMarketHistoryEntry\n',
        'import com.unciv.logic.market.MilitaryMarketHistoryEntry\n'
        'import com.unciv.logic.market.MilitaryMarketListing\n',
        'GameInfo listing import')
    src = replace_one(src,
        '    var militaryMarketHistory = ArrayList<MilitaryMarketHistoryEntry>()\n',
        '    var militaryMarketHistory = ArrayList<MilitaryMarketHistoryEntry>()\n'
        '    var militaryMarketListings = ArrayList<MilitaryMarketListing>()\n'
        '    var militaryMarketAILastOrderTurn = HashMap<String, Int>()\n',
        'GameInfo persistent listing/AI data')
    src = replace_one(src,
        '        toReturn.militaryMarketHistory.addAll(militaryMarketHistory.takeLast(100).map { it.copy() })\n',
        '        toReturn.militaryMarketHistory.addAll(militaryMarketHistory.takeLast(100).map { it.copy() })\n'
        '        toReturn.militaryMarketListings.addAll(militaryMarketListings.map { it.copy() })\n'
        '        toReturn.militaryMarketAILastOrderTurn.putAll(militaryMarketAILastOrderTurn)\n',
        'GameInfo save clone')
    return src


def patch_automation(src: str) -> str:
    src = replace_one(src,
        'import com.unciv.logic.market.MilitaryMarket\n',
        'import com.unciv.logic.market.MilitaryMarket\n'
        'import com.unciv.logic.market.MilitaryMarketAI\n',
        'AI import')
    src = replace_one(src,
        '                WorldMarketAI.buyFromMarketOrders(civInfo)\n            }\n',
        '                WorldMarketAI.buyFromMarketOrders(civInfo)\n'
        '            }\n'
        '            GrandStrategyPerf.measure("military_market") {\n'
        '                MilitaryMarketAI.processTurn(civInfo)\n'
        '            }\n',
        'AI once-per-turn market integration')
    return src


def patch_overview(src: str) -> str:
    src = replace_one(src,
        '    internal val persistState by game.settings::overview\n',
        '    internal val persistState by game.settings::overview\n\n'
        '    // Stable tab keys: use the Korean caption for the custom Formations tab.\n'
        '    private fun tabCaption(category: EmpireOverviewCategories): String =\n'
        '        if (category == EmpireOverviewCategories.Formations) "편제" else category.name\n',
        'overview caption helper')
    src = replace_one(src, 'caption = category.name,', 'caption = tabCaption(category),', 'overview addPage')
    src = replace_one(src, 'tabbedPager.replacePage(category.name, tab)',
                      'tabbedPager.replacePage(tabCaption(category), tab)', 'overview replacePage')
    src = replace_one(src, 'tabbedPager.selectPage(category.name)',
                      'tabbedPager.selectPage(tabCaption(category))', 'overview selectPage')
    return src

PATCH = {
    f'{CORE}/logic/GameInfo.kt': patch_gameinfo,
    f'{CORE}/logic/automation/civilization/NextTurnAutomation.kt': patch_automation,
    f'{CORE}/ui/screens/overviewscreen/EmpireOverviewScreen.kt': patch_overview,
}


def preflight():
    if git('rev-parse', '--show-toplevel').stdout.strip() != str(ROOT):
        raise ValueError('Run in repository root /workspaces/Unciv')
    if git('branch', '--show-current').stdout.strip() != BRANCH:
        raise ValueError(f'Wrong branch: expected {BRANCH}. No changes made.')
    if git('merge-base', '--is-ancestor', BASE_COMMIT_PREFIX, 'HEAD', checked=False).returncode != 0:
        raise ValueError('v1.2 base commit not in history. No changes made.')
    for rel, expected in PATHS.items():
        path = ROOT / rel
        if not path.is_file():
            raise ValueError(f'Existing source missing: {rel}')
        actual = sha(path.read_bytes())
        if actual != expected:
            raise ValueError(f'Existing Kotlin file differs: {rel}\n'
                             f'Expected {expected}, found {actual}. No files changed.')
    for rel in NEW:
        if (ROOT / rel).exists():
            raise ValueError(f'New Kotlin path already exists: {rel}. No files changed.')
    for rel in ALL:
        if rel not in PATCH:
            source = HERE / 'src' / rel
            if not source.is_file():
                raise ValueError(f'Archive missing {rel}')


def prepare() -> dict[Path, bytes]:
    preflight()
    changes: dict[Path, bytes] = {}
    for rel in ALL:
        src = HERE / 'src' / rel
        if rel in PATCH:
            data = PATCH[rel]((ROOT / rel).read_text(encoding='utf-8')).encode('utf-8')
        else:
            data = src.read_bytes()
        changes[ROOT / rel] = data
    if len(changes) != 7:
        raise ValueError(f'Wrong number of changed files: {len(changes)}')
    return changes


def verify():
    if git('branch', '--show-current').stdout.strip() != BRANCH:
        raise ValueError('Wrong branch during verification')
    for rel in ALL:
        if not (ROOT / rel).is_file():
            raise ValueError(f'Missing after install: {rel}')
    game = (ROOT / f'{CORE}/logic/GameInfo.kt').read_text()
    ai = (ROOT / f'{CORE}/logic/automation/civilization/NextTurnAutomation.kt').read_text()
    overview = (ROOT / f'{CORE}/ui/screens/overviewscreen/EmpireOverviewScreen.kt').read_text()
    for item, content in [('militaryMarketListings', game),
                          ('militaryMarketAILastOrderTurn', game),
                          ('MilitaryMarketAI.processTurn(civInfo)', ai),
                          ('tabCaption(category)', overview)]:
        if item not in content:
            raise ValueError(f'Missing integration marker: {item}')
    for rel in ALL - set(PATCH):
        if (ROOT / rel).read_bytes() != (HERE / 'src' / rel).read_bytes():
            raise ValueError(f'Invalid source after install: {rel}')
    print('VERIFY OK: 군수 시장 v1.3 · AI 거래 · 판매 등록 · 한글 UI')


def apply(changes: dict[Path, bytes]):
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d_%H%M%S_%f')
    backup = ROOT.parent / 'GS_military_backup' / f'v13_{stamp}'
    before = {p: p.read_bytes() if p.exists() else None for p in changes}
    backup.mkdir(parents=True, exist_ok=False)
    for path, data in before.items():
        if data is not None:
            dest = backup / path.relative_to(ROOT)
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(data)
    print('BACKUP:', backup)
    try:
        for path, data in changes.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        verify()
    except Exception:
        for path, data in before.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
        raise
    print('APPLY OK: 7 Kotlin files ready for compilation')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument('--check', action='store_true')
    modes.add_argument('--apply', action='store_true')
    modes.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    try:
        if args.verify:
            verify()
        else:
            changes = prepare()
            if args.check:
                print('CHECK OK: safe to apply the seven-file military market v1.3 patch')
            else:
                apply(changes)
    except Exception as e:
        print(f'PATCH ERROR: {e}')
        raise SystemExit(1)


if __name__ == '__main__':
    main()
