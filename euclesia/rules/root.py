from ..data import MCEntityCategory, MOBS_ALL
from .constants import *
from .helpers import RuleHelper
from .vanilla.adventure.root import get_adventure_rules
from .vanilla.end.root import get_end_rules
from .vanilla.entities.root import get_entities_rules
from .vanilla.husbandry.root import get_husbandry_rules
from .vanilla.nether.root import get_nether_rules
from .vanilla.story.root import get_story_rules


def set_rules(world) -> None:

    helper = RuleHelper(world)

    all_advancement_rules = {
        **get_story_rules(helper),
        **get_nether_rules(helper),
        **get_end_rules(helper),
        **get_adventure_rules(helper),
        **get_husbandry_rules(helper),
    }

    for advancement_name, condition in all_advancement_rules.items():
        world.set_rule(world.multiworld.get_location(f"{ADVANCEMENT_PREFIX}{advancement_name}", world.player), condition)


    all_mob_unlock_rules = get_entities_rules(helper)

    for mob_name, condition in all_mob_unlock_rules.items():

        if mob_name not in MOBS_ALL:
            raise KeyError(f"Mob {mob_name} not in MOBS_ALL")

        mob_data = MOBS_ALL[mob_name]

        prefix = f"{ENTITY_KILL_PREFIX}"
        if mob_data.category == MCEntityCategory.BOSS:
            prefix = f"{BOSS_KILL_PREFIX}"

        world.set_rule(world.multiworld.get_location(f"{prefix}{mob_name}", world.player), condition)