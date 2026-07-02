"""Do the access rules actually REQUIRE mob locks, structure locks, and region gating?

Reachability with the full pool only proves nothing dead-locks; it does not prove a rule is not
too loose. This does a differential / leave-one-out test under the heaviest locked config:

  1. Count how many advancement rules reference each gate kind (Entity Unlock / Structure Unlock /
     each Region) — a rule that gates on it has the corresponding AST leaf.
  2. For a sample gate, collect the FULL pool MINUS that one unlock item and show the dependent
     advancements flip to unreachable. If they stay reachable, the gate is cosmetic (a leak).
  3. Region: with the Nether region made unreachable (drop its unlock), Nether advancements must
     become unreachable.

Run: python tools/verify_gates.py
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
from worlds.minecraft.logic.ast import (  # noqa: E402
    And, AtLeast, Const, Has, Or, ReachLocation, ReachRegion,
)

WORLD = AutoWorldRegister.world_types["Minecraft [AEM]"]
OPTS = {
    "blazeandcave": 1, "challenge_sanity": 1, "kill_sanity": 1, "villager_trust": 1,
    "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"},
    "structure_unlock": {"All"}, "boss_list": {"All"}, "advancements_required": 125,
}


def leaves(node):
    """Yield every primitive leaf (Has / ReachRegion / ReachLocation / Const) in the tree."""
    if isinstance(node, (And, Or, AtLeast)):
        for c in node.children:
            yield from leaves(c)
    else:
        yield node


def main():
    mw = setup_multiworld(WORLD, options=OPTS)
    world = mw.worlds[1]
    p = world.player
    rules = world.logic_rules

    # 1. How many advancement rules reference each gate kind?
    ent = struct = 0
    region_ct = {"Nether": 0, "The End": 0, "Overworld": 0}
    ent_items, struct_items = set(), set()
    for name, rule in rules.items():
        if not name.startswith("Advancement:"):
            continue
        for lf in leaves(rule):
            if isinstance(lf, Has) and lf.item.startswith("Entity Unlock: "):
                ent += 1
                ent_items.add(lf.item)
            elif isinstance(lf, Has) and lf.item.startswith("Structure Unlock: "):
                struct += 1
                struct_items.add(lf.item)
            elif isinstance(lf, ReachRegion) and lf.region in region_ct:
                region_ct[lf.region] += 1
    print("Advancement rules referencing each gate (leaf occurrences):")
    print(f"  Entity Unlock leaves:    {ent}  ({len(ent_items)} distinct mobs)")
    print(f"  Structure Unlock leaves: {struct}  ({len(struct_items)} distinct structures)")
    print(f"  Region leaves:           {region_ct}")
    print()

    def full_state(exclude=frozenset()):
        st = CollectionState(mw)
        for it in mw.itempool:
            if it.name not in exclude:
                st.collect(it, prevent_sweep=True)
        return st

    def dependents(item_name):
        """Advancement locations whose rule has a Has(item_name) leaf."""
        out = []
        for name, rule in rules.items():
            if name.startswith("Advancement:") and any(
                isinstance(lf, Has) and lf.item == item_name for lf in leaves(rule)
            ):
                out.append(name)
        return out

    def diff(item_name, label):
        deps = dependents(item_name)
        if not deps:
            print(f"[{label}] no advancement depends on '{item_name}' — skipped")
            return
        full = full_state()
        minus = full_state(exclude={item_name})
        flipped = [d for d in deps
                   if mw.get_location(d, p).can_reach(full)
                   and not mw.get_location(d, p).can_reach(minus)]
        still = [d for d in deps if mw.get_location(d, p).can_reach(minus)]
        print(f"[{label}] '{item_name}': {len(deps)} dependent advs; "
              f"{len(flipped)} became UNREACHABLE without it, {len(still)} still reachable")
        print(f"    sample flipped: {flipped[:3]}")
        if still:
            print(f"    !! still reachable WITHOUT the unlock (possible leak): {still[:5]}")

    # 2. Mob lock + structure lock leave-one-out
    diff("Entity Unlock: Blaze", "MOB LOCK")
    diff("Structure Unlock: Nether Fortress", "STRUCT LOCK")

    # 3. Region gating: drop the Nether dimension unlock, Nether advancements must die.
    nether_advs = [name for name, rule in rules.items()
                   if name.startswith("Advancement:")
                   and any(isinstance(lf, ReachRegion) and lf.region == "Nether"
                           for lf in leaves(rule))]
    full = full_state()
    minus_nether = full_state(exclude={"Dimension Unlock: Nether"})
    print(f"\n[REGION] Nether reachable with full pool: {minus_nether.can_reach_region('Nether', p)} "
          f"(without Dimension Unlock: Nether)")
    reach_full = [n for n in nether_advs if mw.get_location(n, p).can_reach(full)]
    reach_minus = [n for n in reach_full if mw.get_location(n, p).can_reach(minus_nether)]
    print(f"[REGION] Nether-gated advs reachable with full pool: {len(reach_full)}; "
          f"still reachable without Nether unlock: {len(reach_minus)}")
    if reach_minus:
        print(f"    !! Nether advs reachable without reaching the Nether (leak): {reach_minus[:5]}")


if __name__ == "__main__":
    main()
