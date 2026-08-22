"""Every location a rule names must be a location this seed created.

A rule reaches another check with ``loc(<name>)`` (RuleHelper.reached). AP resolves that through
``CollectionState.can_reach_location``, which is a dict lookup — name a location the seed never
created and it raises ``KeyError`` instead of returning False.

That failure is nasty precisely because it is intermittent. The reference usually sits inside an
``any_of``, so Python short-circuits before touching it, and whether fill ever evaluates that branch
depends on the seed. The bug this script exists to catch cost two generations in six of one config:
'Hero of the Village' is challenge-framed, so ``challenge_sanity: false`` (the DEFAULT) creates no
such location, while three villager-gift routes still asked to reach it.

Every existing verifier missed it. ``verify_all_options`` runs at MAXIMUM restriction, which turns
challenge_sanity ON and so creates the very location that was missing; all three verify_yamls set it
on too. So this check deliberately sweeps the configurations where locations are ABSENT — sanity
options off, packs off — which is the opposite axis to "everything on".

No fill is run: this is a pure structural check over the rules, so it is seconds, not minutes.

Run from anywhere:
    python tools/verify_rule_refs.py
"""
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.logic.root import build_location_rules  # noqa: E402

WORLD = AutoWorldRegister.world_types["AEMinecraft"]

# The axis that matters is which locations are ABSENT, so every case here turns something OFF.
CASES = {
    "defaults (challenge_sanity + kill_sanity off)": {},
    "sanity options off, villager_trust on": {
        "challenge_sanity": 0, "kill_sanity": 0, "villager_trust": 1,
    },
    "every lock on but challenge_sanity off": {
        "challenge_sanity": 0, "kill_sanity": 1, "villager_trust": 1,
        "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"},
        "structure_unlock": {"All"}, "knowledge_gates": {"All"}, "boss_list": {"All"},
    },
    "nether start, challenge_sanity off": {
        "challenge_sanity": 0, "start_dimension": "nether", "knowledge_gates": {"All"},
    },
    "BACAP on, challenge_sanity off": {
        "challenge_sanity": 0, "blazeandcave": 1,
    },
    # Control: with challenge_sanity ON the challenge-frame locations exist. If a case ever fails
    # here but passes above, the reference is to something the sanity options do not control.
    "control — challenge_sanity on": {"challenge_sanity": 1, "kill_sanity": 1},
}


def loc_targets(rule) -> set[str]:
    """Every location name reached by a rule tree."""
    found: set[str] = set()

    def walk(node: dict) -> None:
        if node["k"] == "loc":
            found.add(node["l"])
        for child in node.get("c", ()):
            walk(child)

    walk(rule.to_dict())
    return found


def check(label: str, options: dict) -> int:
    world = setup_multiworld(WORLD, options=options).worlds[1]
    active = set(world._get_active_locations())
    problems = 0
    for graph, glitch in (("strict", False), ("glitch", True)):
        targets: set[str] = set()
        for rule in build_location_rules(world, glitch=glitch).values():
            targets |= loc_targets(rule)
        # Event locations are internal and created in create_regions, not _get_active_locations.
        dangling = sorted(t for t in targets - active if not t.startswith("__"))
        status = "OK" if not dangling else f"{len(dangling)} DANGLING"
        print(f"  {graph:<7} {len(targets):3d} loc() targets  {status}")
        for name in dangling:
            print(f"      names a location this seed never created: {name}")
        problems += len(dangling)
    return problems


def main() -> int:
    problems = 0
    for label, options in CASES.items():
        print(f"[{label}]")
        problems += check(label, options)
        print()
    print("RESULT:", "PASS" if problems == 0 else f"FAIL ({problems} dangling references)")
    return 0 if problems == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
