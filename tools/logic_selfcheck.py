"""Validates the exported logic graph against Archipelago's own reachability.

For a solo Minecraft multiworld, this:
  1. runs generation up to set_rules,
  2. builds the logic export (minecraft.logic_export.build_logic_export),
  3. for several received-item sets, compares — for every location — the export
     interpreter's reachability against AP's CollectionState.can_reach_location.

This is the Phase-2 gate: the Java evaluator will interpret the very same export, so
if Python's interpreter matches AP here, the export faithfully reproduces AP logic.

Run from anywhere (it puts the ArchipelagoClone checkout on sys.path):
    python tools/logic_selfcheck.py
"""
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)  # AP expects to run from its root (data files, etc.)

from BaseClasses import CollectionState  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_solo_multiworld  # noqa: E402
from worlds.minecraft.logic_export import build_logic_export  # noqa: E402


# ---------------------------------------------------------------------------
# Pure-Python interpreter of the exported logic (mirrors what Java will do).
# Inputs are RECEIVED items only (counts); we never sweep unchecked locations.
# ---------------------------------------------------------------------------
class ExportEvaluator:
    """Least-fixed-point evaluator of the exported logic graph.

    The rules are *monotone* (only const/has/region/loc/and/or — no negation), so region
    and location reachability is the least fixed point: start everything at False (origin
    True) and re-evaluate until nothing new becomes reachable. This mirrors AP's monotonic
    region sweep and resolves cyclic dependencies correctly (a region reachable only via a
    location whose rule cycles back to that region stays as AP computes it).

    A naive DFS-with-cycle-guard instead caches the temporary False seeded during recursion,
    which poisons sibling locations evaluated mid-cycle (see the Nether ↔ Ice Bucket Challenge
    cycle) and yields order-dependent false negatives. The Java evaluator must use this LFP
    approach too — not a one-shot memoised DFS.
    """

    def __init__(self, export: dict, item_counts: dict[str, int]):
        self.origin = export["origin"]
        self.regions = export["regions"]
        self.locations = export["locations"]
        self.definitions = export.get("definitions", {})  # shared subtrees behind {"k":"ref"}
        self.items = item_counts
        self._resolving: set[int] = set()
        self._region_reach: dict[str, bool] = {}
        self._loc_reach: dict[str, bool] = {}
        self._solve()

    def has(self, item: str, n: int) -> bool:
        return self.items.get(item, 0) >= n

    def _eval(self, node: dict) -> bool:
        # Reads the current reachability snapshot; never recurses into region/location solving.
        k = node["k"]
        if k == "const":
            return bool(node["v"])
        if k == "has":
            return self.has(node["i"], node["n"])
        if k == "region":
            return self._region_reach.get(node["r"], False)
        if k == "loc":
            return self._loc_reach.get(node["l"], False)
        if k == "and":
            return all(self._eval(c) for c in node["c"])
        if k == "or":
            return any(self._eval(c) for c in node["c"])
        if k == "atleast":
            return sum(1 for c in node["c"] if self._eval(c)) >= node["n"]
        if k == "ref":
            ref_id = node["id"]
            definition = self.definitions.get(str(ref_id))
            if definition is None or ref_id in self._resolving:
                return False  # acyclic by construction; guard is defensive (mirrors Java)
            self._resolving.add(ref_id)
            try:
                return self._eval(definition)
            finally:
                self._resolving.discard(ref_id)
        raise ValueError(f"unknown node kind: {k}")

    def _solve(self) -> None:
        self._region_reach = {self.origin: True}
        self._loc_reach = {}
        changed = True
        while changed:  # monotone ⇒ terminates (each pass can only flip Falses to True)
            changed = False
            for from_region, entrances in self.regions.items():
                for entrance in entrances:
                    to = entrance["to"]
                    if not self._region_reach.get(to, False) \
                            and self._region_reach.get(from_region, False) \
                            and self._eval(entrance["rule"]):
                        self._region_reach[to] = True
                        changed = True
            for name, loc in self.locations.items():
                if not self._loc_reach.get(name, False) \
                        and self._region_reach.get(loc["region"], False) \
                        and self._eval(loc["rule"]):
                    self._loc_reach[name] = True
                    changed = True

    def can_reach_region(self, region: str) -> bool:
        return self._region_reach.get(region, False)

    def can_reach_location(self, name: str) -> bool:
        return self._loc_reach.get(name, False)


def make_state(world, item_names_with_counts: dict[str, int]) -> CollectionState:
    """A CollectionState holding exactly the given received items (no sweep)."""
    state = CollectionState(world.multiworld)
    for name, count in item_names_with_counts.items():
        for _ in range(count):
            state.collect(world.create_item(name), prevent_sweep=True)
    return state


def item_sets(world):
    """Yield (label, {item_name: count}) received-item scenarios to test."""
    yield "none", {}

    # Everything in the generated itempool (progression view of "full logic").
    full: dict[str, int] = {}
    for item in world.multiworld.itempool:
        full[item.name] = full.get(item.name, 0) + 1
    yield "all_itempool", full

    # A couple of partial sets touching dimension/material/knowledge gates.
    yield "nether_keys", {
        "Dimension Unlock: Nether": 1,
        "Knowledge: Pyromaniac": 1,
        "Progressive Material Handling": 3,
    }
    yield "mats_only", {"Progressive Material Handling": 6}


def main() -> int:
    world_type = AutoWorldRegister.world_types["Minecraft"]
    multiworld = setup_solo_multiworld(world_type)
    world = multiworld.worlds[1]
    player = world.player

    export = build_logic_export(world)
    print(f"export: {len(export['locations'])} locations, "
          f"{sum(len(v) for v in export['regions'].values())} entrances")

    total_mismatch = 0
    for label, counts in item_sets(world):
        state = make_state(world, counts)
        ev = ExportEvaluator(export, counts)
        mismatches = []
        for name in export["locations"]:
            if name.startswith("__"):
                continue  # synthetic tiles (the AP tab-root) aren't real AP locations
            ap = state.can_reach_location(name, player)
            mine = ev.can_reach_location(name)
            if ap != mine:
                mismatches.append((name, ap, mine))
        status = "OK" if not mismatches else f"{len(mismatches)} MISMATCH"
        print(f"  [{label}] {len(export['locations'])} locations -> {status}")
        for name, ap, mine in mismatches[:15]:
            print(f"      {name}: AP={ap} export={mine}")
        total_mismatch += len(mismatches)

    print("RESULT:", "PASS" if total_mismatch == 0 else f"FAIL ({total_mismatch} mismatches)")
    return 0 if total_mismatch == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
