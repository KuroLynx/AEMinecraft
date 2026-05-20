from euclesia.rules.helpers import RuleHelper
from euclesia.rules.vanilla.adventure.root import get_adventure_rules
from euclesia.rules.vanilla.end.root import get_end_rules
from euclesia.rules.vanilla.husbandry.root import get_husbandry_rules
from euclesia.rules.vanilla.nether.root import get_nether_rules
from euclesia.rules.vanilla.story.root import get_story_rules


def set_rules(world) -> None:

    helper = RuleHelper(world)

    all_rules = {
        **get_story_rules(helper),
        **get_nether_rules(helper),
        **get_end_rules(helper),
        **get_adventure_rules(helper),
        **get_husbandry_rules(helper),
    }

    for advancement_name, condition in all_rules.items():
        world.set_rule(world.multiworld.get_location(advancement_name, world.player), condition)
