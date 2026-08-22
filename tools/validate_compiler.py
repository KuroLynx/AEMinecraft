"""Measure the trigger compiler's coverage against the curated vanilla rules.

For every vanilla advancement: compile its manifest criteria (logic/triggers.py) and compare to the
hand-authored rule (logic/engine.py). Vanilla keeps its curated rules authoritative — this is purely
a report of how far the derived logic gets on its own, to guide which trigger handlers to add and
which advancements genuinely need an override.

Run:
    python tools/validate_compiler.py            # summary
    python tools/validate_compiler.py --list      # + per-advancement compiled/fallback breakdown
"""
import json
import os
import sys

AP_ROOT = r"C:\Users\benja\PycharmProjects\ArchipelagoClone"
sys.path.insert(0, AP_ROOT)
os.chdir(AP_ROOT)

from test.general import setup_multiworld  # noqa: E402
from worlds.AutoWorld import AutoWorldRegister  # noqa: E402
from worlds.minecraft_aem.data import LOCATIONS_ADVANCEMENT  # noqa: E402
from worlds.minecraft_aem.logic.acquisition import RuleHelper  # noqa: E402
from worlds.minecraft_aem.logic.engine import collect_advancement_rules  # noqa: E402
from worlds.minecraft_aem.logic.triggers import TriggerCompiler  # noqa: E402

WORLD = AutoWorldRegister.world_types["AEMinecraft"]
MANIFEST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "minecraft", "packs", "vanilla_26_1", "manifest.json")


def main() -> int:
    show_list = "--list" in sys.argv
    manifest = json.load(open(MANIFEST, encoding="utf-8"))

    mw = setup_multiworld(WORLD, seed=1)
    world = mw.worlds[1]
    helper = RuleHelper(world)
    compiler = TriggerCompiler(helper)

    # game_id -> display name (curated rules are keyed by display name; manifest by game_id).
    name_by_gid = {data.game_id: loc.removeprefix("Advancement: ")
                   for loc, data in LOCATIONS_ADVANCEMENT.items()}
    curated = collect_advancement_rules(helper)

    compiled, fallback = [], []
    matches = 0
    by_trigger_compiled, by_trigger_fallback = {}, {}

    for gid, record in manifest.items():
        primary_triggers = sorted({c.get("trigger") for c in record.get("criteria", {}).values()})
        tkey = ",".join(t.split(":")[-1] for t in primary_triggers if t)
        rule = compiler.compile(record)
        if rule is None:
            fallback.append(gid)
            by_trigger_fallback[tkey] = by_trigger_fallback.get(tkey, 0) + 1
            continue
        compiled.append(gid)
        by_trigger_compiled[tkey] = by_trigger_compiled.get(tkey, 0) + 1
        name = name_by_gid.get(gid)
        cur = curated.get(name)
        if cur is not None and cur.to_dict() == rule.to_dict():
            matches += 1

    total = len(manifest)
    print(f"advancements: {total}")
    print(f"  compiled (derived a rule): {len(compiled)}")
    print(f"  fell back (override/parent needed): {len(fallback)}")
    print(f"  compiled rule byte-identical to curated: {matches}")
    print()
    print("compiled by trigger-set:")
    for k, n in sorted(by_trigger_compiled.items(), key=lambda kv: -kv[1]):
        print(f"  {n:3d}  {k}")
    print("fell back by trigger-set:")
    for k, n in sorted(by_trigger_fallback.items(), key=lambda kv: -kv[1]):
        print(f"  {n:3d}  {k}")

    if show_list:
        print("\n--- compiled ---")
        for gid in compiled:
            print(f"  {gid}")
        print("\n--- fell back ---")
        for gid in fallback:
            print(f"  {gid}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
