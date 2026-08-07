"""Find advancements that are DEAD under maximum restriction.

The strictest configuration the game offers: every structure locked, every mob category
(incl. bosses) locked, villager trust on, kill/challenge sanity on — for BOTH start dimensions.
With the *entire* item pool collected (so every lock's unlock item is held), every active location
MUST be reachable. Any location unreachable here is a logic false-negative: its rule has no
satisfiable path even though, in the real game, the item/advancement is obtainable. Those are the
"write all the possible paths" gaps.

Run from anywhere:
    python tools/verify_all_options.py
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

MAX_OPTIONS = {
    "villager_trust": 1,
    "kill_sanity": 1,
    # 2 = "all", not 1 = "frames": this sweep exists to cover the MOST locations, and `frames` holds
    # back BACAP's challenges tab. Only bites in the BACAP pass below — with blazeandcave off there
    # is no such tab and 1 and 2 are the same seed.
    "challenge_sanity": 2,
    "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"},
    "structure_unlock": {"All"},
    "boss_list": {"All"},
    # "All", not the option's default: the default deliberately leaves the chest, crafting table and
    # furnace ungated, and those three gate the widest part of the tree, so only "All" is the strictest
    # configuration this check is supposed to test.
    "knowledge_gates": {"All"},
    "advancements_required": 125,
}


def full_state(world) -> CollectionState:
    state = CollectionState(world.multiworld)
    for item in world.multiworld.itempool:
        state.collect(item, prevent_sweep=True)
    return state


def check(start_dimension: str, bacap: bool = False) -> int:
    opts = dict(MAX_OPTIONS, start_dimension=start_dimension)
    if bacap:
        opts["blazeandcave"] = 1
    mw = setup_multiworld(WORLD, options=opts)
    world = mw.worlds[1]
    p = world.player
    state = full_state(world)
    locs = list(mw.get_locations(p))
    unreachable = sorted(l.name for l in locs if not state.can_reach_location(l.name, p))
    goal = mw.completion_condition[p](state)
    label = "max-options+BACAP" if bacap else "max-options"
    print(f"[{label} | start={start_dimension}] {len(locs)} locations, "
          f"goal={'OK' if goal else 'FAIL'}")
    if unreachable:
        print(f"  {len(unreachable)} DEAD (unreachable with FULL pool + all locks unlocked):")
        for n in unreachable:
            print(f"      {n}")
    else:
        print("  every location reachable: OK")
    return len(unreachable) + (0 if goal else 1)


def main() -> int:
    problems = 0
    for start in ("overworld", "nether"):
        problems += check(start)
        print()
    # BACAP separately: it is where the challenges tab, and most of the derived position/enter_block
    # logic, actually exist. Without this pass a dead BACAP advancement is invisible here.
    for start in ("overworld", "nether"):
        problems += check(start, bacap=True)
        print()
    print("RESULT:", "PASS" if problems == 0 else f"FAIL ({problems} dead/goal problems)")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
