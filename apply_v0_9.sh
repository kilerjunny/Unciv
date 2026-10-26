#!/usr/bin/env bash
set -euo pipefail
cd /workspaces/Unciv
[ "$(git branch --show-current)" = "grand-strategy-formations" ] || { echo 'Switch to grand-strategy-formations first'; exit 1; }
[ -z "$(git status --porcelain --untracked-files=no -- . ":(exclude)apply_v0_9.sh")" ] || { echo "Uncommitted changes found; stop"; exit 1; }
python3 - <<'PY'
from pathlib import Path
folder=Path('core/src/com/unciv/logic/automation/civilization')
p=folder/'GrandStrategyAI.kt'
assert not p.exists(), 'GrandStrategyAI.kt already exists'
p.write_text('''package com.unciv.logic.automation.civilization

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus

/** Read-only strategic assessment. v0.9 does not change vanilla AI decisions yet. */
internal object GrandStrategyAI {
    enum class Priority { EconomicRecovery, Security, Diplomacy, Expansion }

    fun assess(civ: Civilization): Priority {
        val income = civ.stats.statsForNextTurn.gold
        if (civ.gold < 0 || (civ.gold < 100 && income < 0f)) return Priority.EconomicRecovery
        val atWar = civ.diplomacy.values.any {
            it.otherCiv.isMajorCiv() && it.diplomaticStatus == DiplomaticStatus.War
        }
        if (atWar) return Priority.Security
        val knownMajorCivs = civ.getKnownCivs().count { it.isMajorCiv() && it.isAlive() }
        if (knownMajorCivs > 0 && civ.cities.size <= 2) return Priority.Diplomacy
        return Priority.Expansion
    }
}
''')
p=folder/'NextTurnAutomation.kt'
s=p.read_text()
needle='        val civView = GameView(civInfo.gameInfo, civInfo).civView\n'
assert s.count(needle)==1, 'AI entry point mismatch'
s=s.replace(needle, needle+'''        // v0.9: calculate a strategic priority without changing existing AI behavior.
        if (tradeAndChangeState && civInfo.isMajorCiv()) GrandStrategyAI.assess(civInfo)
''',1)
p.write_text(s)
PY
git add core/src/com/unciv/logic/automation/civilization/GrandStrategyAI.kt core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt
git diff --cached --check
git commit -m 'v0.9: add read-only AI strategic priority assessment'
git push origin grand-strategy-formations
