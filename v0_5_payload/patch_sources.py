#!/usr/bin/env python3
"""Apply narrowly-scoped source edits for v0.5, refusing unexpected upstream changes."""
from pathlib import Path


def replace_once(source: str, original: str, replacement: str, title: str) -> str:
    count = source.count(original)
    if count != 1:
        raise ValueError(f"{title}: expected exactly one anchor, found {count}. No patches applied.")
    return source.replace(original, replacement, 1)


def patched_sources(source_files: dict[str, str]) -> dict[str, str]:
    root = 'core/src/'
    ui = 'com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt'
    rank = 'com/unciv/ui/screens/victoryscreen/RankingType.kt'
    civ = 'com/unciv/logic/civilization/Civilization.kt'
    screen = 'com/unciv/ui/screens/victoryscreen/VictoryScreenCivRankings.kt'
    workflow = '.github/workflows/androidBuild.yml'
    out = dict(source_files)
    out[root + ui] = Path('v0_5_payload/FormationOverviewTab.kt').read_text(encoding='utf-8')
    out[root + 'grandstrategy/rankings/GrandStrategyMilitaryRankings.kt'] = Path(
        'v0_5_payload/GrandStrategyMilitaryRankings.kt').read_text(encoding='utf-8')
    out[root + rank] = replace_once(
        source_files[root + rank],
        '    Force({ ImageGetter.getImage("OtherIcons/Shield") }, \'F\'),\n',
        '    Force({ ImageGetter.getImage("OtherIcons/Shield") }, \'F\'),\n'
        '    ArmyForce("육군력", { ImageGetter.getImage("OtherIcons/Shield") }, \'L\'),\n'
        '    NavyForce("해군력", { ImageGetter.getImage("OtherIcons/Shield") }, \'V\'),\n'
        '    AirForce("공군력", { ImageGetter.getImage("OtherIcons/Shield") }, \'Q\'),\n'
        '    AsymmetricForce("비대칭 전력", { ImageGetter.getImage("OtherIcons/Shield") }, \'X\'),\n',
        'RankingType')
    out[root + civ] = replace_once(
        source_files[root + civ],
        '                RankingType.Force -> getMilitaryMight()\n',
        '                RankingType.Force -> getMilitaryMight()\n'
        '                RankingType.ArmyForce, RankingType.NavyForce, RankingType.AirForce,\n'
        '                RankingType.AsymmetricForce ->\n'
        '                    grandstrategy.rankings.GrandStrategyMilitaryRankings.power(this, category)\n',
        'Civilization')
    out[root + screen] = replace_once(
        source_files[root + screen],
        '        for (category in RankingType.filteredEntries(worldScreen.gameInfo.gameParameters)) {\n',
        '        // Avoid empty air/missile columns in Renaissance-only games.\n'
        '        val visibleCategories = RankingType.filteredEntries(worldScreen.gameInfo.gameParameters)\n'
        '            .filter { category ->\n'
        '                when (category) {\n'
        '                    RankingType.AirForce, RankingType.AsymmetricForce ->\n'
        '                        majorCivs.any { it.getStatForRanking(category) > 0 }\n'
        '                    else -> true\n'
        '                }\n'
        '            }\n'
        '        for (category in visibleCategories) {\n',
        'VictoryScreenCivRankings')
    extra_watch = [
        "      - 'core/src/com/unciv/ui/screens/victoryscreen/RankingType.kt'\n",
        "      - 'core/src/com/unciv/ui/screens/victoryscreen/VictoryScreenCivRankings.kt'\n",
        "      - 'core/src/com/unciv/logic/civilization/Civilization.kt'\n",
        "      - 'core/src/grandstrategy/rankings/GrandStrategyMilitaryRankings.kt'\n",
    ]
    value = source_files[workflow]
    anchor = "      - 'core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt'\n"
    for entry in extra_watch:
        if entry not in value:
            value = replace_once(value, anchor, anchor + entry, 'Workflow')
    out[workflow] = value
    return out


def main() -> None:
    files = {
        p: Path(p).read_text(encoding='utf-8') for p in (
            'core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt',
            'core/src/com/unciv/ui/screens/victoryscreen/RankingType.kt',
            'core/src/com/unciv/logic/civilization/Civilization.kt',
            'core/src/com/unciv/ui/screens/victoryscreen/VictoryScreenCivRankings.kt',
            '.github/workflows/androidBuild.yml',
        )
    }
    changed = patched_sources(files)
    # All transformations finish in memory before the first file is written.
    for path, content in changed.items():
        destination = Path(path)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(content, encoding='utf-8')
    print('v0.5 source patches successfully applied:', len(changed), 'files')


if __name__ == '__main__':
    main()
