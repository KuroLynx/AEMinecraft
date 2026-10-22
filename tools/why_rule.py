"""Why is an advancement in logic? Prints each rule's cheapest requirement sets (strict and glitch),
optionally after a knock-out: the full pool minus some items, to check a gate is really required.

Heaviest locked config (every mob/structure lock, BACAP, glitch logic). Run:
  python tools/why_rule.py "Bee Our Guest" "Lighten Up"
  python tools/why_rule.py --mc 26.3 --without "Entity Unlock: Bee" "Bee Our Guest"
  python tools/why_rule.py --opt exploration_logic=1 "Atlantis"
A name matches every location starting with "Advancement: <name>".
"""
import argparse
import itertools
import os
import sys

AP_ROOT = os.environ.get("AP_ROOT", r"C:\Users\benja\PycharmProjects\ArchipelagoClone")
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

import logging  # noqa: E402
logging.disable(logging.WARNING)

from BaseClasses import CollectionState  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from test.general import setup_multiworld  # noqa: E402
from worlds.minecraft_aem.logic.ast import And, AtLeast, Const, Has, Or, ReachLocation, ReachRegion  # noqa: E402
from worlds.minecraft_aem.logic.root import build_location_rules  # noqa: E402

CAP = 40  # requirement sets kept per node; plenty to see the cheapest routes


def prune(sets):
    out = []
    for s in sorted(set(sets), key=len):
        if not any(o <= s for o in out):
            out.append(s)
    return out[:CAP]


def dnf(node, rules, memo, seen=()):
    """The rule as minimal AND-sets of leaves (items, @regions), smallest first."""
    if id(node) in memo:
        return memo[id(node)]
    if isinstance(node, Const):
        r = [frozenset()] if node.value else []
    elif isinstance(node, Has):
        r = [frozenset({node.item if node.count == 1 else f"{node.item} x{node.count}"})]
    elif isinstance(node, ReachRegion):
        r = [frozenset({"@" + node.region})]
    elif isinstance(node, ReachLocation):
        sub = rules.get(node.location)
        r = ([frozenset({"loc:" + node.location})] if sub is None or node.location in seen
             else dnf(sub, rules, memo, seen + (node.location,)))
    elif isinstance(node, And):
        r = [frozenset()]
        for child in node.children:
            r = prune([a | b for a in r for b in dnf(child, rules, memo, seen)])
    elif isinstance(node, Or) or (isinstance(node, AtLeast) and node.n == 1):
        r = prune([s for child in node.children for s in dnf(child, rules, memo, seen)])
    elif isinstance(node, AtLeast):
        r = prune([frozenset().union(*parts)
                   for combo in itertools.combinations(node.children, node.n)
                   for parts in itertools.product(*[dnf(c, rules, memo, seen) for c in combo])])
    else:
        r = [frozenset({"?" + type(node).__name__})]
    memo[id(node)] = r
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="+")
    ap.add_argument("--mc", default="26.1.2")
    ap.add_argument("--without", action="append", default=[], help="knock-out: item left out of the full pool")
    ap.add_argument("--routes", type=int, default=6)
    ap.add_argument("--opt", action="append", default=[], help="extra option, key=int value")
    args = ap.parse_args()

    world_type = AutoWorldRegister.world_types["AEMinecraft"]
    mw = setup_multiworld(world_type, options={
        "blazeandcave": 1, "kill_sanity": 1, "villager_trust": 1, "glitch_logic": 1,
        "mob_spawn_lock": {"passive", "neutral", "hostile", "boss"}, "structure_unlock": {"All"},
        "boss_list": {"Ender Dragon"}, "advancements_required": 50, "minecraft_version": args.mc,
        **{k: int(v) for k, v in (o.split("=", 1) for o in args.opt)}})
    world = mw.worlds[1]
    strict = world.logic_rules
    glitch = build_location_rules(world, glitch=True)
    locations = sorted(loc.name for loc in mw.get_locations(world.player))

    state = None
    if args.without:
        state = CollectionState(mw)
        for item in mw.itempool:
            if item.name not in args.without:
                state.collect(item, prevent_sweep=True)
        state.sweep_for_advancements()

    for wanted in args.names:
        matches = [n for n in locations if n.startswith(f"Advancement: {wanted}")]
        if not matches:
            print(f"?? no location starts with 'Advancement: {wanted}'")
        for name in matches:
            print(f"===== {name}")
            if state is not None:
                print(f"  without {args.without}: strict={strict[name](state)} "
                      f"glitch={glitch.get(name, strict[name])(state)}")
            for label, rules in (("strict", strict), ("glitch", glitch)):
                if not args.routes:
                    break   # expanding a big rule (a raid, every effect) is combinatorial: skip it
                for s in dnf(rules[name], strict, {})[:args.routes]:
                    print(f"  {label}: " + " + ".join(sorted(s)))


if __name__ == "__main__":
    main()
