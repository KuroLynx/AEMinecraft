from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def a_seedy_place(helper: RuleHelper) -> dict:
    return {
        A_A_SEEDY_PLACE: helper.any_of(
            helper.knowledge("Hoe Handling"),
            helper.any_village(),
        )
    }
