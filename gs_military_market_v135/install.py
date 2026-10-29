#!/usr/bin/env python3
"""Grand Strategy / Unciv military marketplace v1.3.5.

Patch v1.3.4 to allow city-state procurement and supply, and loosen AI military
purchase constraints without allowing stranded units or global free passage.

Works on the previously installed v1.3.4 files, whether or not they are
committed. Modifies ONLY MilitaryMarket.kt and MilitaryMarketAI.kt.
"""
import argparse
import datetime
import hashlib
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
BRANCH = "feature/military-market-v13"
FILES = {
    "market": "core/src/com/unciv/logic/market/MilitaryMarket.kt",
    "ai": "core/src/com/unciv/logic/market/MilitaryMarketAI.kt",
}
REFERENCE = {
    "market": HERE / "reference" / "MilitaryMarket.kt",
    "ai": HERE / "reference" / "MilitaryMarketAI.kt",
}


def replace_once(src, old, new, label):
    amount = src.count(old)
    if amount != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {amount}")
    return src.replace(old, new, 1)


def build_market(src):
    src = replace_once(src,
        "            buyer.isMajorCiv() && seller.isMajorCiv() &&",
        "            (buyer.isMajorCiv() || buyer.isCityState) &&\n"
        "            (seller.isMajorCiv() || seller.isCityState) &&",
        "city-state trading eligibility")
    src = replace_once(src,
        "        if (buyer.isDefeated() || buyer.cities.isEmpty() || !buyer.isMajorCiv()) return emptyList()",
        "        if (buyer.isDefeated() || buyer.cities.isEmpty() ||\n"
        "            !(buyer.isMajorCiv() || buyer.isCityState)) return emptyList()",
        "city-state buyers can browse")
    src = replace_once(src,
        "        for (seller in game.civilizations) {",
        "        // City-states sell their currently constructable units just like AI majors.\n"
        "        // Put them early so the 100-offer catalogue limit won't hide them.\n"
        "        for (seller in game.civilizations.sortedByDescending { it.isCityState }) {",
        "city-state offer visibility")
    return src


def build_ai(src):
    src = replace_once(src,
        '''    private const val MIN_GOLD_RESERVE = 100
    private const val COOLDOWN_TURNS = 12
    private const val MAX_ACTIVE_CONTRACTS = 24''',
        '''    // Procurement is throttled by cooldown, one active order per buyer, and a
    // global cap; reserve cash separately to avoid starving city-state economies.
    private const val COOLDOWN_TURNS = 6
    private const val MAX_ACTIVE_CONTRACTS = 32

    private fun reserveFor(civ: Civilization): Int = if (civ.isCityState) 25 else 50

    private fun hasDemand(civ: Civilization): Boolean {
        if (civ.isAtWar()) return true
        val soldiers = civ.units.getCivUnits().count { it.isMilitary() }
        // Relaxed peacetime ceiling; city-states with one city can still import.
        return soldiers < maxOf(8, civ.cities.size * 8)
    }''',
        "AI policy and reserve")
    src = replace_once(src,
        '''        val spare = (civ.gold - MIN_GOLD_RESERVE).coerceAtLeast(0)
        val fraction = if (atWar) civ.gold * 2 / 3 else civ.gold / 2
        return minOf(spare, fraction)''',
        '''        val spare = (civ.gold - reserveFor(civ)).coerceAtLeast(0)
        // More willing to import while retaining a minimum liquidity cushion.
        val fraction = if (atWar) (civ.gold.toLong() * 9 / 10).toInt()
            else (civ.gold.toLong() * 4 / 5).toInt()
        return minOf(spare, fraction)''',
        "AI larger purchasing budget")
    src = replace_once(src,
        '''        if (!civ.isAI() || !civ.isMajorCiv() || civ.isDefeated() ||
            civ.cities.isEmpty() || civ.gold <= 0) return 0''',
        '''        if (!civ.isAI() || !(civ.isMajorCiv() || civ.isCityState) || civ.isDefeated() ||
            civ.cities.isEmpty() || civ.gold <= 0) return 0''',
        "city-state budget savings")
    src = replace_once(src,
        '''        if (!atWar && civ.units.getCivUnits().count { it.isMilitary() } >= civ.cities.size * 5)
            return 0''',
        '''        if (!hasDemand(civ)) return 0''',
        "military demand budget guard")
    src = replace_once(src,
        '''        // These targets satisfy BOTH the 100G liquidity floor and the AI's
        // 50% peaceful / 2/3 wartime purchase-budget limit.
        val target = if (atWar)
            maxOf(cheapestPrice + MIN_GOLD_RESERVE, (cheapestPrice * 3 + 1) / 2)
        else
            maxOf(cheapestPrice + MIN_GOLD_RESERVE, cheapestPrice * 2)''',
        '''        // Target is the minimum treasury that satisfies both the cash reserve
        // and the 80% peaceful / 90% wartime purchase-budget rule.
        val target = if (atWar)
            maxOf(cheapestPrice + reserveFor(civ), (cheapestPrice * 10 + 8) / 9)
        else
            maxOf(cheapestPrice + reserveFor(civ), (cheapestPrice * 5 + 3) / 4)''',
        "consistent savings target")
    src = replace_once(src,
        '''        if (!civ.isAI() || !civ.isMajorCiv() || civ.isDefeated()) return''',
        '''        if (!civ.isAI() || !(civ.isMajorCiv() || civ.isCityState) || civ.isDefeated()) return''',
        "city-state procurement automation")
    src = replace_once(src,
        '''        if (game.turns - last < COOLDOWN_TURNS || civ.gold < MIN_GOLD_RESERVE) return''',
        '''        if (game.turns - last < COOLDOWN_TURNS || civ.gold <= reserveFor(civ)) return''',
        "minimum gold to order")
    src = replace_once(src,
        '''        val militaryCount = civ.units.getCivUnits().count { it.isMilitary() }
        if (!atWar && militaryCount >= civ.cities.size * 5) return''',
        '''        if (!hasDemand(civ)) return''',
        "military demand when ordering")
    src = replace_once(src,
        '''                val uniqueBonus = if (unit.uniqueTo != null || unit.replaces != null) 30 else 0
                val score = unit.getForceEvaluation().toDouble() / offer.price * 100 + buyerPreference + uniqueBonus''',
        '''                val uniqueBonus = if (unit.uniqueTo != null || unit.replaces != null) 30 else 0
                val cityStateBonus = if (seller.isCityState) 12 else 0
                val score = unit.getForceEvaluation().toDouble() / offer.price * 100 +
                    buyerPreference + uniqueBonus + cityStateBonus''',
        "city-state supply weight")
    src = replace_once(src,
        '''        var affordable = 0
        var candidates = 0''',
        '''        var affordable = 0
        var candidates = 0
        var cityStateCandidates = 0''',
        "city-state demand diagnostic")
    src = replace_once(src,
        '''            if (!buyer.isAI() || !buyer.isMajorCiv() || buyer.isDefeated() ||''',
        '''            if (!buyer.isAI() || !(buyer.isMajorCiv() || buyer.isCityState) || buyer.isDefeated() ||''',
        "city-states included in diagnostics")
    src = replace_once(src,
        '''            if (buyer.gold <= MIN_GOLD_RESERVE) continue''',
        '''            if (buyer.gold <= reserveFor(buyer)) continue''',
        "city-state gold reserve diagnostics")
    src = replace_once(src,
        '''            val militaryCount = buyer.units.getCivUnits().count { it.isMilitary() }
            if (!atWar && militaryCount >= buyer.cities.size * 5) continue''',
        '''            if (!hasDemand(buyer)) continue''',
        "AI demand diagnostics")
    src = replace_once(src,
        '''            if (feasible) candidates++''',
        '''            if (feasible) {
                candidates++
                if (buyer.isCityState) cityStateCandidates++
            }''',
        "count city-state candidates")
    src = replace_once(src,
        '''            "예산 충족 ${affordable}국 · 현재 발주 후보 ${candidates}국"''',
        '''            "예산 충족 ${affordable}국 · 현재 발주 후보 ${candidates}국 " +
            "(도시국가 ${cityStateCandidates}국 포함)"''',
        "city-state diagnostic output")
    return src

BUILDERS = {"market": build_market, "ai": build_ai}


def git(*args):
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)


def sha(data):
    return hashlib.sha256(data).hexdigest()[:12]


def source_files():
    return {k: REFERENCE[k].read_text(encoding="utf-8") for k in FILES}


def validate_repo():
    top = git("rev-parse", "--show-toplevel")
    if top.returncode or top.stdout.strip() != str(ROOT):
        raise RuntimeError("Extract ZIP inside the Unciv repository root")
    b = git("branch", "--show-current")
    if b.returncode or b.stdout.strip() != BRANCH:
        raise RuntimeError(f"Expected branch {BRANCH}, got {b.stdout.strip()}")
    ancestry = git("merge-base", "--is-ancestor", "6f5f775f1", "HEAD")
    if ancestry.returncode:
        raise RuntimeError("v1.3 base commit is not in this branch")


def run(mode):
    validate_repo()
    originals = source_files()
    patched = {k: BUILDERS[k](originals[k]) for k in FILES}
    current = {k: (ROOT / FILES[k]).read_text(encoding="utf-8") for k in FILES}
    if mode == "verify":
        mismatches = [FILES[k] for k in FILES if current[k] != patched[k]]
        if mismatches:
            raise RuntimeError("VERIFY FAILED, mismatches:\n" + "\n".join(mismatches))
        print("VERIFY OK: city-states included, relaxed AI procurement, safe transit preserved")
        return
    differences = []
    for key in FILES:
        if current[key] != originals[key]:
            if current[key] == patched[key]:
                differences.append(key)
            else:
                raise RuntimeError("Unexpected source state for " + FILES[key] +
                                   f" (sha256 {sha(current[key].encode())}), no changes")
    if mode == "check":
        if len(differences) == len(FILES):
            print("CHECK OK: patch already applied; use --verify")
        else:
            print("CHECK OK: recognized v1.3.4 sources, " +
                  str(len(FILES)-len(differences)) + " file(s) to patch")
        return
    needs = [key for key in FILES if key not in differences]
    if not needs:
        print("APPLY OK: already patched")
        return
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d_%H%M%S_%f")
    backup = ROOT.parent / "GS_military_backup" / ("v135_" + stamp)
    for key in needs:
        dest = backup / FILES[key]
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(current[key], encoding="utf-8")
    try:
        for key in needs:
            (ROOT / FILES[key]).write_text(patched[key], encoding="utf-8")
        for key in FILES:
            if (ROOT / FILES[key]).read_text(encoding="utf-8") != patched[key]:
                raise RuntimeError("Post-write verification failed: " + FILES[key])
    except BaseException:
        for key in needs:
            (ROOT / FILES[key]).write_text(current[key], encoding="utf-8")
        raise
    print("APPLY OK: " + str(len(needs)) + " Kotlin files updated, backup at " + str(backup))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    args = p.add_mutually_exclusive_group(required=True)
    for name in ("check", "apply", "verify"):
        args.add_argument("--" + name, action="store_true")
    opts = p.parse_args()
    try:
        run("check" if opts.check else "apply" if opts.apply else "verify")
    except (RuntimeError, OSError, ValueError) as e:
        print("PATCH ERROR: " + str(e), file=sys.stderr)
        raise SystemExit(1)
