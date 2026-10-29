#!/usr/bin/env python3
"""Non-destructive Grand Strategy v0.3 patch for a checked-out Unciv source tree.

Run: python3 apply_to_unciv.py /path/to/Unciv
Requires a source layout matching 2026-10-16 master a8b1e0d62b237af4dcaa79e49c3a041cae31b588.
Refuses ambiguous source patterns and keeps .grandstrategy.bak backups.
"""
import pathlib
import shutil
import sys

HERE = pathlib.Path(__file__).resolve().parent
if len(sys.argv) != 2:
    sys.exit("Usage: python3 apply_to_unciv.py /path/to/Unciv")
root = pathlib.Path(sys.argv[1]).expanduser().resolve()
base = root / "core/src/com/unciv"
if not (base / "logic/civilization/Civilization.kt").is_file():
    sys.exit(f"Invalid Unciv root: {root}")

# Match exactly one source anchor before touching any file.
changes = {
    "logic/civilization/Civilization.kt": [
        (
            "    var civID = \"\"",
            "    var civID = \"\"\n\n" +
            "    /** Grand Strategy formation data serialized in the game save. */\n" +
            "    var grandStrategyFormations = grandstrategy.formations.FormationSnapshot()",
        ),
        (
            "        toReturn.civID = civID",
            "        toReturn.civID = civID\n" +
            "        toReturn.grandStrategyFormations = grandStrategyFormations.copy(\n" +
            "            formations = grandStrategyFormations.formations.map { it.copy() },\n" +
            "            assignments = grandStrategyFormations.assignments.map { it.copy() }\n" +
            "        )",
        ),
    ],
    "ui/screens/overviewscreen/EmpireOverviewCategories.kt": [
        (
            '    Politics("OtherIcons/Politics", \'P\', Align.top) {',
            '    Formations("OtherIcons/Shield", \'F\', Align.topLeft) {\n' +
            '        override fun createTab(viewingPlayer: CivView, overviewScreen: EmpireOverviewScreen, persistedData: EmpireOverviewTabPersistableData?) =\n' +
            '                FormationOverviewTab(viewingPlayer, overviewScreen)\n' +
            '        override fun showDisabled(viewingPlayer: CivView) = viewingPlayer.isSpectator()\n' +
            '    },\n' +
            '    Politics("OtherIcons/Politics", \'P\', Align.top) {',
        ),
    ],
    "logic/civilization/managers/TurnManager.kt": [
        (
            '        if (civInfo.isSpectator()) return',
            '        if (civInfo.isSpectator()) return\n\n' +
            '        // Grand Strategy: eliminate stale unit references once the new turn starts.\n' +
            '        val formationManager = grandstrategy.formations.FormationManager(civInfo.grandStrategyFormations)\n' +
            '        formationManager.reconcileLiveUnits(civInfo.units.getCivUnits().map { it.id.toString() }.toSet())\n' +
            '        civInfo.grandStrategyFormations = formationManager.snapshot()',
        ),
    ],
}

out = {}
for rel, rules in changes.items():
    path = base / rel
    if not path.is_file():
        sys.exit(f"Missing expected Unciv file: {path}")
    contents = path.read_text(encoding="utf-8")
    for before, after in rules:
        if after in contents:
            continue  # safely re-runnable
        if contents.count(before) != (2 if before.startswith("    var civID") else 1):
            sys.exit(f"ABORT (no files modified): expected exactly one anchor in {rel}: {before!r}")
        contents = contents.replace(before, after, 1)
    out[path] = contents

for path, contents in out.items():
    if path.read_text(encoding="utf-8") == contents:
        continue
    backup = path.with_name(path.name + ".grandstrategy.bak")
    if not backup.exists():
        shutil.copy2(path, backup)
    path.write_text(contents, encoding="utf-8")
    print("Patched", path.relative_to(root))

copy_targets = {
    HERE / "FormationManager.kt": root / "core/src/grandstrategy/formations/FormationManager.kt",
    HERE / "FormationOverviewTab.kt": base / "ui/screens/overviewscreen/FormationOverviewTab.kt",
}
for src, dst in copy_targets.items():
    dst.parent.mkdir(parents=True, exist_ok=True)
    if dst.exists() and dst.read_bytes() != src.read_bytes():
        backup = dst.with_name(dst.name + ".grandstrategy.bak")
        if not backup.exists(): shutil.copy2(dst, backup)
    shutil.copy2(src, dst)
    print("Installed", dst.relative_to(root))

print("Patch applied. Build and test against your local Unciv source checkout.")
