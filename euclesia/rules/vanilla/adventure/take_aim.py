from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def take_aim(helper: RuleHelper) -> dict:
    return {
        A_TAKE_AIM: helper.all_of(
            helper.knowledge("Sharpshooter"),
            helper.can_get_arrow()
        )
    }
