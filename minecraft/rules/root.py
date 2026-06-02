from worlds.generic.Rules import set_rule

from ..data import MCEntityCategory, MOBS_ALL
from .ast import Const
from .constants import *
from .helpers import RuleHelper
from .vanilla.adventure.root import get_adventure_rules
from .vanilla.end.root import get_end_rules
from .vanilla.entities.root import get_entities_rules
from .vanilla.husbandry.root import get_husbandry_rules
from .vanilla.nether.root import get_nether_rules
from .vanilla.story.root import get_story_rules


def set_rules(world ) -> None:

    helper = RuleHelper(world)

    # Locations actually created this seed depend on options (challenge_sanity, kill_sanity).
    # Rules are defined for every advancement/mob, so skip those whose location was not created
    # — otherwise get_location raises KeyError.
    existing_locations = {location.name for location in world.multiworld.get_locations(world.player)}

    # Capture the final rule node per location so it can be serialized for the mod
    # (build_logic_export). These are the exact same nodes handed to set_rule.
    exported_rules = {}

    all_advancement_rules = {
        **get_story_rules(helper),
        **get_nether_rules(helper),
        **get_end_rules(helper),
        **get_adventure_rules(helper),
        **get_husbandry_rules(helper),
    }

    for advancement_name, condition in all_advancement_rules.items():
        location_name = f"{ADVANCEMENT_PREFIX}{advancement_name}"
        if location_name in existing_locations:
            set_rule(world.multiworld.get_location(location_name, world.player), condition)
            exported_rules[location_name] = condition


    all_mob_unlock_rules = get_entities_rules(helper)

    for mob_name, condition in all_mob_unlock_rules.items():

        if mob_name not in MOBS_ALL:
            raise KeyError(f"Mob {mob_name} not in MOBS_ALL")

        mob_data = MOBS_ALL[mob_name]

        prefix = f"{ENTITY_KILL_PREFIX}"
        if mob_data.category == MCEntityCategory.BOSS:
            prefix = f"{BOSS_KILL_PREFIX}"

        # Hostile mobs need a real weapon to fight, except low-level ones that can be punched.
        if mob_data.category == MCEntityCategory.HOSTILE and mob_name not in FIST_KILLABLE_MOBS:
            condition = helper.all_of(condition, helper.can_kill())

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