from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def hot_stuff(helper: RuleHelper) -> dict:
    return {
        A_HOT_STUFF: helper.any_of(
            helper.can_craft_bucket()
        ),
    }
