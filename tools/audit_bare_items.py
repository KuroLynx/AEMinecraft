"""Every ITEM whose compiled `acquire()` asks for nothing — the bare-region test, applied to
acquisition instead of to advancements.

`audit_bare_rules.py` asks the question of a CHECK: does its access rule demand an item or another
check? This asks it of the thing a rule is usually built out of. The two find different bugs, and the
item one finds them earlier: an item that compiles to a bare `Region(Overworld)` doesn't only make
the advancement that wants it free, it *deletes* the gates of every rule that ORs it with a real
source — `_unique_or` absorption reads `A ∨ (A ∧ B)` as `A`, so one free path takes the structure
and mob-lock branches with it (see the 2026-09-06 farmland-crop report in
`docs/item_gate_bug_reports.md`, which is what this script was written to generalize).

An item reads as free for one of two reasons, and only a human can tell them apart:

  * it IS free — kelp, dirt, a poppy, an oak log. Being in the Overworld really is the whole price.
  * its only modeled source is circular — mining back a block that exists only because something
    placed it (a crop somebody planted, a decorated pot somebody assembled, copper that weathered
    where it stood). `_acquire_from_sources` catches the crafted case through the record's
    `recipes` key; a placed-only block with no recipe is exactly the blind spot.

So the report prints, for each free item, the sources its record actually lists. A free item whose
only source is `mining` a block named after itself (or after a block that had to be built) is the
suspect shape; a free item that lists nothing but `mining: [short_grass]` is fine.

Run from anywhere:
    python tools/audit_bare_items.py                  # every lock on (the decisive config)
    python tools/audit_bare_items.py --all            # every config in CASES
    python tools/audit_bare_items.py --verbose        # print each item's compiled rule too
"""
import json
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from test.general import setup_multiworld  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from worlds.minecraft_aem.logic.acquisition import RuleHelper, _acquisition_table  # noqa: E402

WORLD = AutoWorldRegister.world_types["AEMinecraft"]

# Same reasoning as audit_bare_rules.CASES: a gate only exists when an option put an AP item behind
# it, so an item can read "free" merely because this seed has nothing to demand. With every lock on,
# whatever still asks for nothing is ungated on its own merits.
ALL_LOCKS = {
    "challenge_sanity": 1, "kill_sanity": 1, "villager_trust": 1,
    "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"},
    "structure_unlock": {"All"}, "knowledge_gates": {"All"}, "boss_list": {"All"},
}

CASES = {
    "everything on + BACAP": {**ALL_LOCKS, "blazeandcave": 1},
    "everything on": dict(ALL_LOCKS),
    "default (overworld start)": {},
    "nether start": {"start_dimension": "nether"},
}

# The source keys an acquisition record can carry, in the order they read best in the report.
SOURCE_KEYS = ("recipes", "mining", "silk_mining", "drops", "structures", "archaeology",
               "trades", "gameplay")


def _sources(base: str) -> str:
    """One line naming what the item's record offers, so a free item can be judged on the spot."""
    record = _acquisition_table().get(base, {})
    parts = []
    for key in SOURCE_KEYS:
        values = record.get(key)
        if not values:
            continue
        if key == "recipes":
            parts.append(f"recipes×{len(values)}")
        else:
            parts.append(f"{key}: {', '.join(map(str, values))}")
    return "; ".join(parts) or "<no sources at all>"


def audit(world) -> tuple[list[tuple[str, list[str]]], list[str]]:
    """(free items with the regions they ask for, items with no source at all) for this world."""
    helper = RuleHelper(world, glitch=False)
    free, missing = [], []
    for base in sorted(_acquisition_table()):
        node = helper.acquire(f"minecraft:{base}")
        if node is None:
            missing.append(base)          # unobtainable: the opposite failure, and worth seeing
            continue
        gated, regions = node.gate_summary()
        if not gated:
            free.append((base, sorted(regions) or ["<none — always true>"], node))
    return free, missing


def main() -> int:
    argv = set(sys.argv[1:])
    sys.stdout.reconfigure(encoding="utf-8")
    verbose = "--verbose" in argv or "-v" in argv
    cases = CASES if "--all" in argv else {"everything on + BACAP": CASES["everything on + BACAP"]}

    for label, options in cases.items():
        world = setup_multiworld(WORLD, options=options).worlds[1]
        free, missing = audit(world)
        total = len(_acquisition_table())
        print(f"[{label}] {len(free)} of {total} items ask for nothing; "
              f"{len(missing)} have no source at all")
        for base, regions, node in free:
            print(f"  {base:36s} {', '.join(regions)}")
            print(f"      {_sources(base)}")
            if verbose:
                print(f"      rule: {json.dumps(node.to_dict())}")
        if missing:
            print(f"\n  --- NO SOURCE ({len(missing)}) --- {', '.join(missing)}")
        print()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
