#!/usr/bin/env bash
set -euo pipefail
cd /workspaces/Unciv
if [ "$(git branch --show-current)" != 'grand-strategy-formations' ]; then echo 'ERROR: Wrong branch'; exit 1; fi
python3 - <<'PY'
from pathlib import Path
p=Path('core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt')
s=p.read_text()
old='val candidates = units.filter { formations.assignedFormation(it.id.toString()) != selected.id }'
assert s.count(old)==1, 'Expected v0.7 filter not found'
s=s.replace(old,'val candidates = units.filter { formations.assignedFormation(it.id.toString()) == null }')
s=s.replace('배속 / 전속 가능한 유닛 ', '미배속 유닛 ')
old_block='''                    val oldName = formations.assignedFormation(unit.id.toString())?.let {
                        formations.get(it)?.name
                    } ?: "미배속"
                    actionRow(target, unit.displayName() + " / " + oldName, "배속") {'''
assert s.count(old_block)==1, 'Expected old label not found'
s=s.replace(old_block,'                    actionRow(target, unit.displayName() + " / 미배속", "배속") {')
p.write_text(s)
print('v0.8 patch applied')
PY
git add core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt
git diff --cached --check
git commit -m 'v0.8: show only unassigned units in formation picker'
git push origin grand-strategy-formations
echo 'SUCCESS: v0.8 pushed.'
