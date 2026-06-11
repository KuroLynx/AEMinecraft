from worlds.generic.Rules import set_rule

from ..data import MCEntityCategory, MOBS_ALL
from .ast import Const
from .constants import *
from .engine import collect_advancement_rules, collect_entity_rules
from .helpers import RuleHelper


def set_rules(world ) -> None:

    helper = RuleHelper(world)

    # Locations actually created this seed depend on options (challenge_sanity, kill_sanity).
    # Rules are defined for every advancement/mob, so skip those whose location was not created
    # — otherwise get_location raises KeyError.
    existing_locations = {location.name for location in world.multiworld.get_locations(world.player)}

    # Capture the final rule node per location so it can be serialized for the mod
    # (build_logic_export). These are the exact same nodes handed to set_rule.
    exported_rules = {}

    # Rules are auto-discovered from the rule packages (see rules/engine.py) rather than wired by
    # hand: every leaf rule file is collected by walking its package.
    all_advancement_rules = collect_advancement_rules(helper)

    for advancement_name, condition in all_advancement_rules.items():
        location_name = f"{ADVANCEMENT_PREFIX}{advancement_name}"
        if location_name in existing_locations:
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
        # every other mob can be beaten bare-handed via the boat trap, so its rule is just entity()).
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