"""Which checks does logic let you do holding nothing at all?

With inventory_lock at slots 36 every slot starts locked, so nothing can be held until the first
Progressive Inventory Slot arrives. A check logic still reaches then — with every OTHER item in hand —
must need nothing in hand: killing barehanded, walking somewhere, riding. Anything else reachable here
is a rule that needs an item without pricing it (an item's price carries the first slot), and with
slots 36 it can hold the first slot: a softlock.

Grouped by trigger, since one compiler handler usually explains many checks.

    python tools/audit_zero_slot.py                 # every version, vanilla + BACAP
    python tools/audit_zero_slot.py --version 26.3  # one version
"""
import sys
from collections import defaultdict

from audit_bare_rules import ALL_LOCKS  # noqa: E402,F401  (also sets up the AP import path)

from BaseClasses import CollectionState  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.data import MINECRAFT_VERSIONS  # noqa: E402
from worlds.minecraft_aem.logic.root import _manifest  # noqa: E402

SLOT = "Progressive Inventory Slot"
LOCK = {"mode": "progressive", "slots": 36, "slots_per_item": 1, "offhand": False, "armor": False}


def zero_slot_checks(version: str, bacap: bool) -> list[str]:
    mw = setup_multiworld(AutoWorldRegister.world_types["AEMinecraft"], seed=1,
                          options={"minecraft_version": version, "inventory_lock": LOCK,
                                   "blazeandcave": int(bacap)})
    state = CollectionState(mw)
    for item in mw.itempool:
        if item.name != SLOT:
            state.collect(item, prevent_sweep=True)
    state.sweep_for_advancements()
    return sorted(l.name for l in mw.get_locations(1) if l.address is not None and l.can_reach(state)), mw.worlds[1]


def triggers(world, location: str, bacap: bool) -> list[str]:
    data = world.content.ADVANCEMENT_LOCATIONS.get(location)
    if data is None:
        return ["(kill location)"]
    content = world.content
    pack = content.BACAP_PACK if bacap and content.BACAP_PACK else content.BASE_PACK
    record = _manifest(pack).get(data.game_id) or {}
    return sorted({(c.get("trigger") or "?").split(":")[-1] for c in (record.get("criteria") or {}).values()}) or ["?"]


def main(argv):
    sys.stdout.reconfigure(encoding="utf-8")
    versions = [argv[argv.index("--version") + 1]] if "--version" in argv else MINECRAFT_VERSIONS
    for version in versions:
        for bacap in (False, True):
            checks, world = zero_slot_checks(version, bacap)
            by_trigger = defaultdict(list)
            for name in checks:
                by_trigger[", ".join(triggers(world, name, bacap))].append(name.split(": ", 1)[-1])
            print(f"\n=== {version} {'BACAP' if bacap else 'vanilla'}: {len(checks)} checks doable with zero slots")
            for trig, names in sorted(by_trigger.items(), key=lambda kv: (-len(kv[1]), kv[0])):
                print(f"  [{trig}] {len(names)}: {', '.join(names)}")


if __name__ == "__main__":
    main(sys.argv[1:])
