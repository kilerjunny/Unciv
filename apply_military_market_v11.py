#!/usr/bin/env python3
"""Apply a scoped Grand Strategy military market v1.1 two-stage delivery patch to the Unciv source tree.

Usage:
  python3 apply_military_market_v11.py --check
  python3 apply_military_market_v11.py --apply
Run from Codespaces repository root, with the ZIP extracted there.
"""
import argparse
from pathlib import Path
import sys

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path.cwd()


def replace_one(content, old, new, label):
    if new in content:
        return content
    count = content.count(old)
    if count != 1:
        raise ValueError(f"{label}: expected one anchor, found {count}")
    return content.replace(old, new, 1)


def patch_game_info(s):
    s = replace_one(s,
        'import com.unciv.logic.market.MarketSellOrder\n',
        'import com.unciv.logic.market.MarketSellOrder\n'
        'import com.unciv.logic.market.MilitaryMarket\n'
        'import com.unciv.logic.market.MilitaryMarketContract\n'
        'import com.unciv.logic.market.MilitaryMarketHistoryEntry\n',
        'GameInfo import')
    s = replace_one(s,
        '    var worldMarketHistory = ArrayList<MarketTradeRecord>()\n',
        '    var worldMarketHistory = ArrayList<MarketTradeRecord>()\n'
        '    var militaryMarketContracts = ArrayList<MilitaryMarketContract>()\n'
        '    var militaryMarketHistory = ArrayList<MilitaryMarketHistoryEntry>()\n',
        'GameInfo state')
    s = replace_one(s,
        '        toReturn.worldMarketHistory.addAll(worldMarketHistory.takeLast(100).map { it.copy() })\n',
        '        toReturn.worldMarketHistory.addAll(worldMarketHistory.takeLast(100).map { it.copy() })\n'
        '        toReturn.militaryMarketContracts.addAll(militaryMarketContracts.map { it.copy() })\n'
        '        toReturn.militaryMarketHistory.addAll(militaryMarketHistory.takeLast(100).map { it.copy() })\n',
        'GameInfo cloning')
    s = replace_one(s,
        '        GrandStrategyPerf.beginTurn(gameId)\n',
        '        GrandStrategyPerf.beginTurn(gameId)\n'
        '        MilitaryMarket.reconcile(this)\n',
        'GameInfo nextTurn')
    return s


def patch_constructions(s):
    s = replace_one(s,
        'package com.unciv.logic.city\n',
        'package com.unciv.logic.city\n\nimport com.unciv.logic.market.MilitaryMarket\n',
        'CityConstructions import')
    start = s.index('    fun completeConstruction(construction: INonPerpetualConstruction): Boolean {')
    end = s.index('    fun addBuilding(buildingName: String)', start)
    region = s[start:end]
    needle = '        return true\n    }\n\n'
    addition = ('        // A contracted unit was genuinely produced here; attempt overseas delivery.\n'
                '        if (construction is BaseUnit && unit != null) MilitaryMarket.onProduced(city, unit)\n'
                '        return true\n    }\n\n')
    region = replace_one(region,needle,addition,'CityConstructions completion hook')
    return s[:start]+region+s[end:]


def patch_unit_ai(s):
    # This branch's AI path is wrapped with GrandStrategyPerf measurements.
    # Match the actual v1.3 flow instead of assuming a direct automateUnitMoves call.
    s = replace_one(s,
        'import com.unciv.logic.market.WorldMarketAI\n',
        'import com.unciv.logic.market.WorldMarketAI\n'
        'import com.unciv.logic.market.MilitaryMarket\n',
        'NextTurnAutomation import')

    s = replace_one(s,
        '        fun unitsInPriorityOrder() = unitsAtStart.sortedBy { unit -> getUnitPriority(unit, isAtWar) }\n',
        '        fun unitsInPriorityOrder() = unitsAtStart\n'
        '            .filterNot { MilitaryMarket.isReservedUnit(it) }\n'
        '            .sortedBy { unit -> getUnitPriority(unit, isAtWar) }\n',
        'NextTurnAutomation prioritized unit movement')

    # City assaults and settler escorts do not use unitsInPriorityOrder.
    s = replace_one(s,
        '            .mapNotNull { it.militaryUnit }.filter { it.civ == civInfo && (!it.baseUnit.isMelee() || it.health > 30) }.toList()\n',
        '            .mapNotNull { it.militaryUnit }.filter { it.civ == civInfo &&\n'
        '                !MilitaryMarket.isReservedUnit(it) &&\n'
        '                (!it.baseUnit.isMelee() || it.health > 30) }.toList()\n',
        'NextTurnAutomation assault unit filter')

    s = replace_one(s,
        '                it.civ == civInfo\n                    && it.health >= 100\n',
        '                it.civ == civInfo\n                    && !MilitaryMarket.isReservedUnit(it)\n'
        '                    && it.health >= 100\n',
        'NextTurnAutomation settler escort unit filter')
    return s


def patch_market_ui(s):
    s = replace_one(s,
        '    private var performanceOpen = false\n',
        '    private var performanceOpen = false\n    private var militaryMarketOpen = false\n',
        'WorldMarketTradeSection state')
    s = replace_one(s,
        '            if (historyOpen) performanceOpen = false\n',
        '            if (historyOpen) { performanceOpen = false; militaryMarketOpen = false }\n',
        'WorldMarketTradeSection history toggle')
    s = replace_one(s,
        '            if (performanceOpen) historyOpen = false\n',
        '            if (performanceOpen) { historyOpen = false; militaryMarketOpen = false }\n',
        'WorldMarketTradeSection performance toggle')
    s = replace_one(s,
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
        'WorldMarketTradeSection military button')
    return s


def main():
    parser = argparse.ArgumentParser()
    g = parser.add_mutually_exclusive_group(required=True)
    g.add_argument('--check', action='store_true')
    g.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    if not (ROOT / 'core/src/com/unciv/logic/GameInfo.kt').exists():
        raise ValueError('Run the script from the Unciv repository root')

    editors = {
        'core/src/com/unciv/logic/GameInfo.kt': patch_game_info,
        'core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt': patch_unit_ai,
        'core/src/com/unciv/logic/city/CityConstructions.kt': patch_constructions,
        'core/src/com/unciv/ui/screens/overviewscreen/WorldMarketTradeSection.kt': patch_market_ui,
    }
    changes = {}
    for rel, op in editors.items():
        path = ROOT / rel
        original = path.read_text(encoding='utf-8')
        updated = op(original)
        if updated == original:
            print('already applied:', rel)
        changes[path] = updated

    copied = []
    updated_files = []
    for source in sorted((SCRIPT_DIR / 'files').rglob('*.kt')):
        rel = source.relative_to(SCRIPT_DIR / 'files')
        dst = ROOT / rel
        legacy = SCRIPT_DIR / 'legacy_files' / rel
        if not dst.exists():
            copied.append((source, dst))
        elif dst.read_bytes() == source.read_bytes():
            pass  # v1.1 already applied
        elif legacy.exists() and dst.read_bytes() == legacy.read_bytes():
            updated_files.append((source, dst))
        else:
            raise ValueError(f'Existing file conflicts with v1.1 or was edited manually: {rel}')

    if args.check:
        print('CHECK OK: patch anchors match current source. Planned new files:', len(copied), 'upgrades:', len(updated_files))
        return

    # No writes until every source and file conflict has been checked.
    for p, updated in changes.items():
        if p.read_text(encoding='utf-8') != updated:
            p.write_text(updated, encoding='utf-8')
            print('updated:', p.relative_to(ROOT))
    for src,dst in copied + updated_files:
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(src.read_bytes())
        print('installed:', dst.relative_to(ROOT))
    print('Done. Verify: ./gradlew :core:compileKotlin --no-daemon')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError) as exc:
        print('PATCH ERROR:', exc, file=sys.stderr)
        sys.exit(1)
