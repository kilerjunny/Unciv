#!/usr/bin/env bash
set -euo pipefail
BRANCH="grand-strategy-formations"
FILES=(
  "core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt"
  "core/src/com/unciv/ui/screens/victoryscreen/RankingType.kt"
  "core/src/com/unciv/logic/civilization/Civilization.kt"
  "core/src/com/unciv/ui/screens/victoryscreen/VictoryScreenCivRankings.kt"
  "core/src/grandstrategy/rankings/GrandStrategyMilitaryRankings.kt"
  ".github/workflows/androidBuild.yml"
)

printf '[1/5] Checking Grand Strategy branch and working tree...\n'
[[ "$(git rev-parse --show-toplevel 2>/dev/null)" = "$PWD" ]] || {
  echo "ERROR: Run this from the Unciv repository root."; exit 1;
}
[[ "$(git branch --show-current)" = "$BRANCH" ]] || {
  echo "ERROR: Wrong branch. Do not run this on master."; exit 1;
}
[[ -f v0_5_payload/FormationOverviewTab.kt && -f v0_5_payload/GrandStrategyMilitaryRankings.kt ]] || {
  echo "ERROR: Unzip the v0.5 ZIP at Unciv root first."; exit 1;
}
for f in "${FILES[@]}"; do
  if [[ -n "$(git status --porcelain -- "$f")" ]]; then
    echo "ERROR: Existing local changes in $f; refusing to overwrite them."
    exit 1
  fi
done
python3 - <<'PY'
import hashlib, subprocess
expected = {
 'core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt': '9da46276b6b33d9b70da35b7668904261ce2d6a0',
 'core/src/com/unciv/ui/screens/victoryscreen/RankingType.kt': '04266b9ce9003c48fadb82e415761a58b2841d34',
 'core/src/com/unciv/logic/civilization/Civilization.kt': 'ac4bd451b06e41330812c8ce9d14fabc6f26a562',
 'core/src/com/unciv/ui/screens/victoryscreen/VictoryScreenCivRankings.kt': '3c0dcc2ccccf8286e6625b62f16efe445b997498',
 '.github/workflows/androidBuild.yml': 'c0db97de962a036fa715e8c36099c547d1d8dad2',
}
for path, sha in expected.items():
    current = subprocess.check_output(['git','hash-object',path], text=True).strip()
    if current != sha:
        raise SystemExit('ERROR: Unexpected source version: '+path+'\nCurrent: '+current+'\nExpected: '+sha+'\nDo not overwrite: ask for an updated patch.')
print('Source version verified; current v0.4 matches expected files.')
PY

printf '[2/5] Installing v0.5 code...\n'
python3 v0_5_payload/patch_sources.py
printf '[3/5] Checking changes...\n'
git diff --check -- "${FILES[@]}"
git diff --stat -- "${FILES[@]}"
printf '[4/5] Committing code without adding the ZIP or old payloads...\n'
git add -- "${FILES[@]}"
git commit -m "v0.5: split-pane formations, unit map navigation and military rankings"
printf '[5/5] Pushing to GitHub to trigger Android build...\n'
git push origin "$BRANCH"
echo "SUCCESS: Go to GitHub Actions > Android build; install the newest APK as an update."
