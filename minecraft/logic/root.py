import json
from importlib.resources import files

from worlds.generic.Rules import set_rule

from ..data import ADVANCEMENT_LOCATIONS, MOBS_ALL, MCEntityCategory
from .acquisition import RuleHelper
from .ast import Const
from .constants import *
from .engine import collect_advancement_rules, collect_entity_rules
from .triggers import TriggerCompiler


def _manifest(pack_name: str) -> dict:
    """A pack's advancement manifest (tools/extract_manifest.py), keyed by advancement game_id."""
    root = __package__.rsplit(".", 1)[0]  # e.g. "worlds.minecraft"
    with files(root).joinpath("packs", pack_name, "manifest.json").open(encoding="utf-8") as f:
        return json.load(f)


def set_rules(world ) -> None:

    helper = RuleHelper(world)

    # Locations actually created this seed depend on options (challenge_sanity, kill_sanity).
    # Rules are defined for every advancement/mob, so skip those whose location was not created
    # — otherwise get_location raises KeyError.
    existing_locations = {loc.name for loc in world.multiworld.get_locations(world.player)}

    # Capture the final rule node per location so it can be serialized for the mod
    # (build_logic_export). These are the exact same nodes handed to set_rule.
    exported_rules = {}

    # Advancement logic is DERIVED from each advancement's Minecraft criteria (the trigger compiler,
    # logic/triggers.py + the acquisition table), so vanilla, mods and datapacks are handled the
    # same way. A curated rule (logic/.../*.py) is only the fallback for advancements whose criteria
    # can't be compiled (skill / situational ones — target_hit, effects_changed, …); a parent-chain
    # reach, then Const(True), are the last resorts.
    # With BACAP on, the criteria come from its manifest (its `minecraft:` rewrites override the
    # vanilla advancements, reusing the same locations; its `blazeandcave:` ones are new locations).
    curated = collect_advancement_rules(helper)              # by display name
    compiler = TriggerCompiler(helper, frozenset(existing_locations))
    manifest = _manifest("bacap" if world.options.blazeandcave else "vanilla_26_1")

    for location_name, loc_data in ADVANCEMENT_LOCATIONS.items():
        if location_name not in existing_locations:
            continue
        record = manifest.get(loc_data.game_id)
        name = location_name.removeprefix(ADVANCEMENT_PREFIX)
        condition = compiler.compile(record) if record is not None else None
        if condition is None:
            condition = curated.get(name)
        if condition is None and record is not None:
            condition = compiler.parent_rule(record)
        if condition is None:
            condition = Const(True)
        set_rule(world.multiworld.get_location(location_name, world.player), condition)
        exported_rules[location_name] = condition


    all_mob_unlock_rules = collect_entity_rules(helper)

    for mob_name, condition in all_mob_unlock_rules.items():

        if mob_name not in MOBS_ALL:
            raise KeyError(f"Mob {mob_name} not in MOBS_ALL")

        mob_data = MOBS_ALL[mob_name]

        prefix = f"{ENTITY_KILL_PREFIX}"
        if mob_data.category == MCEntityCategory.BOSS:
            prefix = f"{BOSS_KILL_PREFIX}"

        # Killability is stated per entity-rule file now (only bosses gate on can_kill / extra gear;
        # every other mob can be beaten bare-handed via the boat trap, so its rule is just entity).
        location_name = f"{prefix}{mob_name}"
        if location_name in existing_locations:
            set_rule(world.multiworld.get_location(location_name, world.player), condition)
            exported_rules[location_name] = condition

    # Locations with no explicit rule are always accessible in AP (set_rule was never called
    # for them). Mirror that in the export so they aren't dropped — notably each tab's `root`
    # advancement, which carries no rule but is a real check. Region reachability still applies
    # in build_logic_export, so e.g. the Nether root only counts once the Nether is reached.
    for location_name in existing_locations:
        exported_rules.setdefault(location_name, Const(True))

    world.logic_rules = exported_rules
