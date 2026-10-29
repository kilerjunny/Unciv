#!/usr/bin/env python3
"""Grand Strategy Unciv - military procurement budget v1.3.4.

Checked against feature/military-market-v13 after the v1.3.3 patch.
Supports --check, --apply, --verify. No repo changes during --check/--verify.
"""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
# When extracted in /workspaces/Unciv/gs_military_market_v134/
FILES = {
    "military": "core/src/com/unciv/logic/market/MilitaryMarketAI.kt",
    "world": "core/src/com/unciv/logic/market/WorldMarketAI.kt",
    "gold": "core/src/com/unciv/logic/automation/civilization/UseGoldAutomation.kt",
    "automation": "core/src/com/unciv/logic/automation/civilization/NextTurnAutomation.kt",
}
BASE_BLOBS = {
    "military": "1a6258172777040e45bc7c8f2171114675ed0f04",
    "world": "1f28768352d13a22207091f4966295733421e30c",
    "gold": "f180f26c0fdd37503abcb0e891bf4eb25abeec1e",
    "automation": "475bf26cba4aa3e89275ea42274157da117528f7",
}

PROTECTED_GOLD_METHOD = '''    /**
     * Protected treasury for a REAL procurement opportunity, not an unconditional tax.
     * The target is the amount at which purchaseBudget() will allow the cheapest
     * deliverable unit. While saving, ordinary spending cannot consume this gold.
     * No viable offer, no transit rights, no demand or a cooldown => no reservation.
     * Recomputed on the AI's turn; no cache or save-file schema change.
     */
    fun protectedGold(civ: Civilization): Int {
        if (!civ.isAI() || !civ.isMajorCiv() || civ.isDefeated() ||
            civ.cities.isEmpty() || civ.gold <= 0) return 0
        val game = civ.gameInfo
        if (game.militaryMarketContracts.size >= MAX_ACTIVE_CONTRACTS ||
            MilitaryMarket.myContracts(civ).isNotEmpty()) return 0
        val last = game.militaryMarketAILastOrderTurn[civ.civID] ?: -1000
        if (game.turns - last < COOLDOWN_TURNS) return 0
        val atWar = civ.isAtWar()
        if (!atWar && civ.units.getCivUnits().count { it.isMilitary() } >= civ.cities.size * 5)
            return 0
        val hasNavalBase = civ.cities.any { it.isNaval() }
        val ceiling = if (atWar) 550 else 350
        val cheapestPrice = MilitaryMarket.offersFor(civ, limit = 100)
            .asSequence()
            .filter { it.price in 1..ceiling }
            .filter { offer ->
                val seller = game.getCivilizationOrNull(offer.sellerId) ?: return@filter false
                val city = seller.cities.firstOrNull { it.id == offer.cityId } ?: return@filter false
                val unit = game.ruleset.units[offer.unitName] ?: return@filter false
                civ.diplomacyFunctions.canPassThroughTiles(seller) &&
                    offer.unitName !in city.cityConstructions.constructionQueue &&
                    unit.isMilitary && unit.getForceEvaluation() > 0 &&
                    (!unit.isWaterUnit || hasNavalBase)
            }
            .minOfOrNull { it.price } ?: return 0
        // These targets satisfy BOTH the 100G liquidity floor and the AI's
        // 50% peaceful / 2/3 wartime purchase-budget limit.
        val target = if (atWar)
            maxOf(cheapestPrice + MIN_GOLD_RESERVE, (cheapestPrice * 3 + 1) / 2)
        else
            maxOf(cheapestPrice + MIN_GOLD_RESERVE, cheapestPrice * 2)
        return minOf(civ.gold, target)
    }

'''


def exactly_replace(contents: str, old: str, new: str, label: str) -> str:
    occurrences = contents.count(old)
    if occurrences != 1:
        raise ValueError(f"{label}: expected exactly one anchor, got {occurrences}")
    return contents.replace(old, new, 1)


def build_updates(original: dict[str, str]) -> dict[str, str]:
    out = dict(original)
    out["military"] = exactly_replace(
        out["military"],
        "    fun processTurn(civ: Civilization) {",
        PROTECTED_GOLD_METHOD + "    fun processTurn(civ: Civilization) {",
        "military protected treasury",
    )

    out["world"] = exactly_replace(
        out["world"],
        "        for (order in orders) {",
        """        // Preserve funds for a feasible military contract before buying resources.
        val protectedMilitaryGold = if (orders.isEmpty()) 0 else MilitaryMarketAI.protectedGold(buyer)

        for (order in orders) {""",
        "world market budget declaration",
    )
    out["world"] = exactly_replace(
        out["world"],
        """            if (buyer.gold.toLong() < order.unitPrice.toLong() * 2L)
                continue""",
        """            if (buyer.gold.toLong() < order.unitPrice.toLong() * 2L ||
                buyer.gold.toLong() - protectedMilitaryGold < order.unitPrice.toLong())
                continue""",
        "world market protected funds",
    )

    out["gold"] = exactly_replace(
        out["gold"],
        """    fun useGold(civ: Civilization) {
        for (unit in civ.units.getCivUnits())""",
        """    fun useGold(civ: Civilization) {
        // Save for attainable build-to-order military units. Upgrades remain military
        // spending; civilian expenditures must stay above the protected treasury.
        val protectedMilitaryGold = MilitaryMarketAI.protectedGold(civ)
        for (unit in civ.units.getCivUnits())""",
        "regular spending budget setup",
    )
    out["gold"] = exactly_replace(
        out["gold"],
        """        if (civ.isMajorCiv())
            useGoldForCityStates(civ)""",
        """        // City-state investment is discretionary: hold it during procurement saving.
        if (civ.isMajorCiv() && protectedMilitaryGold == 0)
            useGoldForCityStates(civ)""",
        "city-state budget protection",
    )
    out["gold"] = exactly_replace(
        out["gold"],
        """            if (civ.gold < statBuyCost || construction.hurryCostModifier > acceptableHurryCostModifier) continue""",
        """            if (civ.gold - protectedMilitaryGold < statBuyCost ||
                construction.hurryCostModifier > acceptableHurryCostModifier) continue""",
        "city construction budget protection",
    )
    out["gold"] = exactly_replace(
        out["gold"],
        """        maybeBuyCityTiles(civ)
    }

    private fun useGoldForCityStates""",
        """        maybeBuyCityTiles(civ, protectedMilitaryGold)
    }

    private fun useGoldForCityStates""",
        "tile spending caller",
    )
    out["gold"] = exactly_replace(
        out["gold"],
        """    private fun maybeBuyCityTiles(civInfo: Civilization) {""",
        """    private fun maybeBuyCityTiles(civInfo: Civilization, protectedMilitaryGold: Int) {""",
        "tile spending signature",
    )
    out["gold"] = exactly_replace(
        out["gold"],
        """                if (civInfo.gold >= goldCostOfTile) {""",
        """                if (civInfo.gold - protectedMilitaryGold >= goldCostOfTile) {""",
        "tile spending guard",
    )

    out["automation"] = exactly_replace(
        out["automation"],
        """        if (tradeAndChangeState && civInfo.isAI()) {
            GrandStrategyPerf.measure("market_supply") {
                WorldMarketAutoSupply.update(civInfo)
            }
            GrandStrategyPerf.measure("market_purchase") {
                WorldMarketAI.buyFromMarketOrders(civInfo)
            }
            GrandStrategyPerf.measure("military_market") {
                MilitaryMarketAI.processTurn(civInfo)
            }
        }""",
        """        if (tradeAndChangeState && civInfo.isAI()) {
            // Military procurement gets first access to treasury; other purchases
            // must respect its savings target for a deliverable unit.
            GrandStrategyPerf.measure("military_market") {
                MilitaryMarketAI.processTurn(civInfo)
            }
            GrandStrategyPerf.measure("market_supply") {
                WorldMarketAutoSupply.update(civInfo)
            }
            GrandStrategyPerf.measure("market_purchase") {
                WorldMarketAI.buyFromMarketOrders(civInfo)
            }
        }""",
        "military before world market ordering",
    )
    return out


def command(*parts: str, check: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(parts, cwd=ROOT, capture_output=True, text=True, check=check)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true")
    group.add_argument("--apply", action="store_true")
    group.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    branch = command("git", "branch", "--show-current", check=True).stdout.strip()
    if branch != "feature/military-market-v13":
        raise SystemExit(f"ERROR: expected feature/military-market-v13, got {branch}")
    if not (ROOT / ".git").exists():
        raise SystemExit(f"ERROR: install directory must be extracted inside repository: {ROOT}")

    original = {k: (ROOT / path).read_text(encoding="utf-8") for k, path in FILES.items()}
    if args.verify:
        # Verify that source differs from GitHub baseline by exactly these changes.
        pristine = {}
        for k, path in FILES.items():
            blob = command("git", "show", f"{BASE_BLOBS[k]}")
            if blob.returncode != 0:
                raise SystemExit("VERIFY ERROR: baseline blob absent from local git; fetch origin first")
            pristine[k] = blob.stdout
        expected = build_updates(pristine)
        mismatched = [FILES[k] for k in FILES if original[k] != expected[k]]
        if mismatched:
            raise SystemExit("VERIFY ERROR: patched content mismatch:\n" + "\n".join(mismatched))
        print("VERIFY OK: 4 Kotlin files match expected military budget changes")
        return

    paths = list(FILES.values())
    modified = command("git", "status", "--porcelain", "--", *paths, check=True).stdout.strip()
    if modified:
        raise SystemExit("ERROR: target Kotlin files have uncommitted changes. Commit or restore before applying.\n" + modified)
    for k, path in FILES.items():
        actual_blob = command("git", "hash-object", path, check=True).stdout.strip()
        if actual_blob != BASE_BLOBS[k]:
            raise SystemExit(f"ERROR: {path} is not the expected v1.3.3 base ({actual_blob}). No changes made")
    patched = build_updates(original)
    for k in FILES:
        if patched[k] == original[k]:
            raise SystemExit(f"ERROR: patch for {k} made no changes")
    if args.check:
        print("CHECK OK: v1.3.3 baseline verified, 4 clean source files, all anchors found")
        return

    # All source validation happens before writing; paths deliberately whitelisted.
    for k, path in FILES.items():
        (ROOT / path).write_text(patched[k], encoding="utf-8")
    print("APPLY OK: protected military budget added to 4 Kotlin files")
    print("Next: python3 gs_military_market_v134/install.py --verify")
    print("Then: ./gradlew :core:compileKotlin --no-daemon --console=plain")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        raise SystemExit(f"PATCH ERROR: {exc}")
