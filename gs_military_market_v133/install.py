#!/usr/bin/env python3
"""Grand Strategy military market v1.3.3: AI demand + unique units + ETA display.

Accepts either clean v1.3 or the optional v1.3.2 applied source state.
Never overwrites unrecognized modifications.
"""
import argparse
import datetime
import hashlib
import subprocess
from pathlib import Path

ROOT = Path.cwd().resolve()
HERE = Path(__file__).resolve().parent
BRANCH = "feature/military-market-v13"
BASE = "6f5f775f1"  # Source commit for original v1.3
FILES = {
    "core/src/com/unciv/logic/market/MilitaryMarketAI.kt": "4f7743ae697ce938bddc91194e62b38f691b3cd6",
    "core/src/com/unciv/ui/screens/overviewscreen/MilitaryMarketSection.kt": "88ecd918e34bb12ca0ad8948cefb682a7d5e3189",
    "core/src/com/unciv/logic/market/MilitaryMarket.kt": "312fd41a6c8d069307360e007bc6167c2f137371",
}

def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True)

def blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def modified_market(base):
    original = '            for (unit in city.cityConstructions.getConstructableUnits()) {\n                if (!unit.isMilitary || unit.isGreatPerson) continue\n                results.add(Offer(seller.civID, city.id, unit.name,\n                    referencePrice(seller, city, unit)))'
    replacement = "            // Prioritize the producer civilisation's unique units before the catalogue cap.\n            val buildable = city.cityConstructions.getConstructableUnits()\n                .filter { it.isMilitary && !it.isGreatPerson }\n                .sortedWith(compareByDescending<BaseUnit> { it.uniqueTo != null || it.replaces != null }\n                    .thenBy { it.name })\n            for (unit in buildable) {\n                results.add(Offer(seller.civID, city.id, unit.name,\n                    referencePrice(seller, city, unit)))"
    if base.count(original) != 1:
        raise ValueError("MilitaryMarket.kt catalogue anchor mismatch; refusing to patch")
    return base.replace(original, replacement)

def expected_files():
    result = {}
    for rel in FILES:
        if rel.endswith("/MilitaryMarket.kt"):
            original = git("show", BASE + ":" + rel)
            if original.returncode:
                raise ValueError("Cannot read baseline MilitaryMarket.kt")
            result[rel] = modified_market(original.stdout.decode("utf-8")).encode("utf-8")
        else:
            path = HERE / "src" / rel
            if not path.is_file():
                raise ValueError("Missing package file: " + rel)
            result[rel] = path.read_bytes()
    return result

def preflight():
    if git("rev-parse", "--show-toplevel").stdout.decode().strip() != str(ROOT):
        raise ValueError("Run from Unciv repository root")
    if git("branch", "--show-current").stdout.decode().strip() != BRANCH:
        raise ValueError("Expected branch " + BRANCH)
    if git("merge-base", "--is-ancestor", BASE, "HEAD").returncode:
        raise ValueError("v1.3 base commit missing from branch history")
    changes = expected_files()
    for rel, original_sha in FILES.items():
        path = ROOT / rel
        if not path.is_file():
            raise ValueError("Missing original source: " + rel)
        current = path.read_bytes()
        # Sources at base v1.3, already-upgraded v1.3.2, and this patch are supported.
        # The last two v1.3.2 market/AI versions have identical target bytes.
        acceptable = {original_sha, blob_sha(changes[rel])}
        if rel.endswith("/MilitaryMarketSection.kt"):
            v132_ui = HERE / "src" / "v132_MilitaryMarketSection.kt"
            if not v132_ui.is_file():
                raise ValueError("Missing supported v1.3.2 compatibility source")
            acceptable.add(blob_sha(v132_ui.read_bytes()))
        if blob_sha(current) not in acceptable:
            raise ValueError("Modified/unrecognized original source: " + rel + " (no changes made)")
    return changes

def verify(changes=None):
    if changes is None:
        changes = expected_files()
    for rel, data in changes.items():
        if (ROOT / rel).read_bytes() != data:
            raise ValueError("Verification failed: " + rel)
    print("VERIFY OK: AI 구매 개선, 고유 병종 우선 표시, 생산 턴 표시")

def apply(changes):
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
    backup = ROOT.parent / "GS_military_backup" / ("v133_" + stamp)
    originals = {ROOT / rel: (ROOT / rel).read_bytes() for rel in changes}
    backup.mkdir(parents=True, exist_ok=False)
    for path, data in originals.items():
        dest = backup / path.relative_to(ROOT)
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
    try:
        for rel, data in changes.items():
            (ROOT / rel).write_bytes(data)
        verify(changes)
    except Exception:
        for path, data in originals.items():
            path.write_bytes(data)
        raise
    print("APPLY OK: backup at " + str(backup))

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    modes = parser.add_mutually_exclusive_group(required=True)
    for mode in ("check", "apply", "verify"):
        modes.add_argument("--" + mode, action="store_true")
    args = parser.parse_args()
    try:
        if args.verify: verify()
        else:
            changes = preflight()
            if args.check: print("CHECK OK: 3 files ready (v1.3 or v1.3.2 compatible)")
            else: apply(changes)
    except Exception as exc:
        print("PATCH ERROR:", exc)
        raise SystemExit(1)
