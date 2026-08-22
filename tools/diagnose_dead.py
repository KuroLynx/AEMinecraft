"""Why is each advancement unreachable even with the FULL item pool?

For the heaviest BACAP config, collect every item, then for each dead advancement walk its
serialized rule AST (world.logic_rules) and print the leaves that evaluate False — the structural
reason it can't be reached. Run: python tools/diagnose_dead.py
"""
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

import logging  # noqa: E402
logging.disable(logging.WARNING)

from BaseClasses import CollectionState  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.logic.ast import (  # noqa: E402
    And, AtLeast, Const, Has, Or, ReachLocation, ReachRegion,
)

WORLD = AutoWorldRegister.world_types["AEMinecraft"]
OPTS = {
    "blazeandcave": 1, "challenge_sanity": 1, "kill_sanity": 1, "villager_trust": 1,
    "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"},
    "structure_unlock": {"All"}, "boss_list": {"All"}, "advancements_required": 125,
}


def why(node, state, depth=0):
    """A compact multi-line trace of the False leaves under `node`."""
    pad = "  " * depth
    if isinstance(node, Const):
        return f"{pad}Const(False)" if not node.value else None
    if isinstance(node, Has):
        return None if state.has(node.item, node.player, node.count) else \
            f"{pad}MISSING Has({node.item} x{node.count})"
    if isinstance(node, ReachRegion):
        return None if state.can_reach_region(node.region, node.player) else \
            f"{pad}UNREACHABLE Region({node.region})"
    if isinstance(node, ReachLocation):
        return None if state.can_reach_location(node.location, node.player) else \
            f"{pad}UNREACHABLE Loc({node.location})"
    if isinstance(node, And):
        if node(state):
            return None
        parts = [why(c, state, depth + 1) for c in node.children]
        parts = [p for p in parts if p]
        return f"{pad}AND fails:\n" + "\n".join(parts)
    if isinstance(node, (Or, AtLeast)):
        if node(state):
            return None
        n = getattr(node, "n", 1)
        parts = [why(c, state, depth + 1) for c in node.children]
        parts = [p for p in parts if p]
        return f"{pad}OR/ATLEAST(need {n}) all-or-too-few fail:\n" + "\n".join(parts)
    return f"{pad}? {type(node).__name__}"


def main():
    mw = setup_multiworld(WORLD, options=OPTS)
    world = mw.worlds[1]
    p = world.player
    state = CollectionState(mw)
    for item in mw.itempool:
        state.collect(item, prevent_sweep=True)

    dead = sorted(loc.name for loc in mw.get_locations(p)
                  if loc.name.startswith("Advancement:")
                  and not state.can_reach_location(loc.name, p))
    print(f"{len(dead)} dead advancements:\n")
    for name in dead:
        rule = world.logic_rules.get(name)
        print(f"=== {name} ===")
        if rule is None:
            print("  (no rule)")
        else:
            print(why(rule, state) or "  (rule passes?!)")
        print()


if __name__ == "__main__":
    main()
