#!/usr/bin/env bash
set -euo pipefail

BRANCH="grand-strategy-formations"
TARGET="core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt"
WORKFLOW=".github/workflows/androidBuild.yml"
SOURCE="v0_4_payload/FormationOverviewTab.kt"

echo "[1/5] Checking repository and branch..."
if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
    echo "ERROR: Open the Unciv GitHub Codespace repository first."
    exit 1
fi
if [[ "$(git branch --show-current)" != "$BRANCH" ]]; then
    echo "ERROR: Expected branch $BRANCH. Switch branches before proceeding."
    exit 1
fi
if [[ ! -f "$TARGET" || ! -f "$WORKFLOW" || ! -f "$SOURCE" ]]; then
    echo "ERROR: Missing file. Upload ZIP to the Unciv repository root, then unzip it."
    exit 1
fi
if ! git diff --quiet -- "$TARGET" "$WORKFLOW" || ! git diff --cached --quiet -- "$TARGET" "$WORKFLOW"; then
    echo "ERROR: Existing uncommitted changes to target files. Review them first; nothing overwritten."
    exit 1
fi

echo "[2/5] Installing v0.4 formation UI..."
cp "$SOURCE" "$TARGET"

# The existing Android workflow watches Gradle files only. Include our UI
# source so this and future formation-UI pushes trigger an APK build.
python3 - <<'PY'
from pathlib import Path
p = Path('.github/workflows/androidBuild.yml')
s = p.read_text(encoding='utf-8')
entry = "      - 'core/src/com/unciv/ui/screens/overviewscreen/FormationOverviewTab.kt'\n"
if entry not in s:
    needle = "      - 'android/build.gradle.kts'\n"
    if needle not in s:
        raise SystemExit('ERROR: Unexpected workflow file; please review it manually.')
    s = s.replace(needle, needle + entry, 1)
    p.write_text(s, encoding='utf-8')
PY

echo "[3/5] Checking changed files..."
git diff --check -- "$TARGET" "$WORKFLOW"
git diff --stat -- "$TARGET" "$WORKFLOW"

echo "[4/5] Committing v0.4..."
git add "$TARGET" "$WORKFLOW"
if git diff --cached --quiet; then
    echo "Nothing to commit. v0.4 appears to be applied already."
    exit 0
fi
git commit -m "v0.4: Collapsible formation tree and compact UI"

echo "[5/5] Pushing to GitHub; this triggers the Android APK build..."
git push origin "$BRANCH"
echo "SUCCESS: Open GitHub Actions > Android build and wait for the new APK artifact."
