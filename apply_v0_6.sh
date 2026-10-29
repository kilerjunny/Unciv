#!/usr/bin/env bash
set -euo pipefail

BRANCH="grand-strategy-formations"
UI="core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt"
EXPECTED_SHA="b930c38609cec8e625f068ee83a8a23dca08ccad"

printf '[1/5] Checking the Grand Strategy branch...\n'
[[ "$(git rev-parse --show-toplevel 2>/dev/null)" == "$PWD" ]] || {
  echo "ERROR: Start in the Unciv repository root."; exit 1;
}
[[ "$(git branch --show-current)" == "$BRANCH" ]] || {
  echo "ERROR: This update must run only on grand-strategy-formations, never master."; exit 1;
}
[[ -f v0_6_payload/FormationOverviewTab.kt ]] || {
  echo "ERROR: Unzip the update at the repository root first."; exit 1;
}
[[ -z "$(git status --porcelain -- "$UI")" ]] || {
  echo "ERROR: FormationOverviewTab.kt has local modifications. Nothing overwritten."; exit 1;
}
actual="$(git hash-object "$UI")"
[[ "$actual" == "$EXPECTED_SHA" ]] || {
  printf 'ERROR: Unexpected v0.5 UI source.\nCurrent: %s\nExpected: %s\nNothing overwritten.\n' "$actual" "$EXPECTED_SHA"
  exit 1
}

printf '[2/5] Installing the v0.6 step-by-step navigator...\n'
cp v0_6_payload/FormationOverviewTab.kt "$UI"

printf '[3/5] Checking diff...\n'
git diff --check -- "$UI"
git diff --stat -- "$UI"

printf '[4/5] Committing UI code (no ZIP/payloads committed)...\n'
git add -- "$UI"
git commit -m "v0.6: stepwise formation navigation and full hierarchy view"

printf '[5/5] Pushing to trigger the Android APK build...\n'
git push origin "$BRANCH"
echo "SUCCESS: Check GitHub Actions > Android build; install the newest APK over Grand Strategy."
