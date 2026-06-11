"""Behavior-equivalence gate for the data-driven architecture refactor.

`fill_slot_data()` (the whole blob shipped to the Fabric mod — logic graph, id maps, lock
maps, trackers, filler/trap effects) is fully *option-driven*, not seed-driven: given the same
option set it is deterministic. So we can snapshot it for a handful of representative option
combos and assert the refactor reproduces it byte-for-byte.

Usage (run from anywhere):
    python tools/snapshot_slotdata.py write    # capture baseline into tools/_slotdata_baseline/
    python tools/snapshot_slotdata.py check     # regenerate and diff against the baseline

`write` BEFORE the refactor (on the checkpoint commit), `check` AFTER. A clean `check` is the
hard "no behavior change" gate for Milestone 1.
"""
import json
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402

WORLD = AutoWorldRegister.world_types["Minecraft"]
BASELINE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_slotdata_baseline")
SEED = 1234567

# Representative option combos. Names are stable file stems; each must exercise a distinct
# branch of the logic/slot_data builders (start dimension, the *_sanity location filters, the
# lock maps, finders, villager trust, advancement goal).
COMBOS: dict[str, dict] = {
    "default": {},
    "nether_start": {"start_dimension": "nether"},
    "kill_and_challenge": {"kill_sanity": True, "challenge_sanity": True, "advancements_required": 50},
    "villager_trust": {"villager_trust": True},
    "mob_and_structure_locks": {
        # Locking every mob category + all structures adds many unlock items, so the location
        # pool must be widened (kill + challenge sanity) or create_items rejects the combo.
        "mob_spawn_lock_category": ["passive", "neutral", "hostile", "boss"],
        "structure_unlock": ["All"],
        "kill_sanity": True,
        "challenge_sanity": True,
    },
    "finders_off": {"structure_finder": "disabled", "biome_finder": "disabled"},
    "finders_start": {"structure_finder": "start", "biome_finder": "start"},
    "everything_nether": {
        "start_dimension": "nether",
        "kill_sanity": True,
        "challenge_sanity": True,
        "villager_trust": True,
        "advancements_required": 30,
        "mob_spawn_lock_category": ["hostile", "boss"],
        "structure_unlock": ["Nether", "Ancient City"],
        "biome_finder": "start",
    },
}


def slotdata_for(options: dict) -> dict:
    mw = setup_multiworld(WORLD, seed=SEED, options=options)
    world = mw.worlds[1]
    return world.fill_slot_data()


def _normalize(node):
    """Recursively sort the children of boolean AST nodes (and / or / atleast). Their order is
    semantically irrelevant — both AP's solver and the mod's Java evaluator treat them as
    commutative — so two exports that differ only in child order are the same logic. Normalising
    lets the equivalence gate tolerate the (legitimate) reordering the auto-discovery walker
    introduces, while still catching any real change to the *set* of children."""
    if isinstance(node, dict):
        node = {k: _normalize(v) for k, v in node.items()}
        if node.get("k") in ("and", "or", "atleast") and isinstance(node.get("c"), list):
            node["c"] = sorted(node["c"], key=lambda ch: json.dumps(ch, sort_keys=True))
        return node
    if isinstance(node, list):
        items = [_normalize(x) for x in node]
        # Scalar-only lists in this slot_data (mob_spawn_lock, boss_list) are built from sets, so
        # their order varies per process (hash seed) and is meaningless to the mod — sort them.
        # AST child arrays hold dicts (sorted above) and are left to that handler.
        if all(isinstance(x, (str, int, float, bool)) for x in items):
            return sorted(items, key=str)
        return items
    return node


def canonical(blob: dict) -> str:
    # sort_keys makes dict ordering irrelevant; _normalize makes boolean-node child order
    # irrelevant; default=str tolerates any stray non-JSON scalar.
    return json.dumps(_normalize(blob), sort_keys=True, indent=2, default=str)


def cmd_write() -> int:
    os.makedirs(BASELINE_DIR, exist_ok=True)
    for name, opts in COMBOS.items():
        text = canonical(slotdata_for(opts))
        with open(os.path.join(BASELINE_DIR, f"{name}.json"), "w", encoding="utf-8") as f:
            f.write(text)
        print(f"  wrote {name}.json ({len(text)} bytes)")
    print(f"baseline written to {BASELINE_DIR}")
    return 0


def cmd_check() -> int:
    if not os.path.isdir(BASELINE_DIR):
        print(f"no baseline at {BASELINE_DIR} — run `write` first.")
        return 2
    mismatches = 0
    for name, opts in COMBOS.items():
        path = os.path.join(BASELINE_DIR, f"{name}.json")
        if not os.path.exists(path):
            print(f"  MISSING baseline for {name}")
            mismatches += 1
            continue
        with open(path, encoding="utf-8") as f:
            # Re-canonicalise the stored baseline so any later normalisation improvement applies to
            # it too (the file is valid JSON written by canonical()).
            expected = canonical(json.loads(f.read()))
        actual = canonical(slotdata_for(opts))
        if actual == expected:
            print(f"  OK   {name}")
        else:
            mismatches += 1
            print(f"  DIFF {name} — writing actual to {name}.actual.json for inspection")
            with open(os.path.join(BASELINE_DIR, f"{name}.actual.json"), "w", encoding="utf-8") as f:
                f.write(actual)
    print()
    print("RESULT:", "PASS (identical slot_data)" if mismatches == 0 else f"FAIL ({mismatches} combos differ)")
    return 0 if mismatches == 0 else 1


def main() -> int:
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "write":
        return cmd_write()
    if mode == "check":
        return cmd_check()
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
