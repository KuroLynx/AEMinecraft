"""Semantic verification that advancement logic correctly handles BOTH start dimensions.

logic_selfcheck.py proves the *exported* graph matches AP. This script asks the higher-level
question the export can't: is the logic itself *correct* for a Nether start?

Two failure modes are checked:

  A. IMPOSSIBLE — with the full progression item pool collected, every active location must be
     reachable for each start_dimension. An advancement unreachable even with all items is a dead
     (ungeneratable) advancement.

  B. TOO LOOSE (the Nether-start danger) — for a Nether start, collect every progression item
     EXCEPT `Dimension Unlock: Overworld`, so the Overworld is permanently unreachable. Only
     genuinely Nether-native advancements may be reachable. Anything Overworld-rooted (Minecraft /
     Husbandry / Adventure roots, etc.) that shows up here is a logic leak: its rule lets a Nether
     player claim it without ever reaching the Overworld.

Run from anywhere:
    python tools/verify_reachability.py
"""
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from BaseClasses import CollectionState  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402

WORLD = AutoWorldRegister.world_types["Minecraft [AEM]"]
OVERWORLD_ITEM = "Dimension Unlock: Overworld"


def state_from(world, *, exclude: set[str] = frozenset()) -> CollectionState:
    """A CollectionState holding the whole itempool minus any excluded item names (no sweep)."""
    state = CollectionState(world.multiworld)
    for item in world.multiworld.itempool:
        if item.name in exclude:
            continue
        state.collect(item, prevent_sweep=True)
    return state


def setup(start_dimension: str):
    mw = setup_multiworld(WORLD, options={"start_dimension": start_dimension})
    return mw, mw.worlds[1]


def check_impossible(start_dimension: str) -> int:
    mw, world = setup(start_dimension)
    p = world.player
    state = state_from(world)
    locs = list(mw.get_locations(p))
    unreachable = sorted(l.name for l in locs if not state.can_reach_location(l.name, p))
    regions = {r: state.can_reach_region(r, p) for r in ("Overworld", "Nether", "The End")}
    goal = mw.completion_condition[p](state)
    print(f"[A full-pool | start={start_dimension}] {len(locs)} locations, "
          f"regions={regions}, goal={'OK' if goal else 'FAIL'}")
    if unreachable:
        print(f"  {len(unreachable)} UNREACHABLE even with full pool:")
        for n in unreachable:
            print(f"      {n}")
    else:
        print("  every location reachable with full pool: OK")
    return len(unreachable) + (0 if goal else 1)


def check_nether_leak() -> int:
    """Nether start, Overworld permanently locked → list every reachable location."""
    mw, world = setup("nether")
    p = world.player
    state = state_from(world, exclude={OVERWORLD_ITEM})
    ow_reached = state.can_reach_region("Overworld", p)
    reachable = sorted(l.name for l in mw.get_locations(p) if state.can_reach_location(l.name, p))
    print(f"[B nether, Overworld LOCKED] Overworld reachable = {ow_reached} "
          f"(must be False) | {len(reachable)} locations reachable without ever leaving the Nether:")
    for n in reachable:
        print(f"      {n}")
    # The Overworld must NOT be reachable without its unlock item; that's the only hard assertion.
    # The reachable list is printed for human audit (each must be genuinely Nether-doable).
    return 0 if not ow_reached else 1


def main() -> int:
    problems = 0
    for start in ("overworld", "nether"):
        problems += check_impossible(start)
        print()
    problems += check_nether_leak()
    print()
    print("RESULT:", "PASS" if problems == 0 else f"FAIL ({problems} hard problems)")
    print("NOTE: review section B's list by hand — every entry must be doable purely in the Nether.")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
