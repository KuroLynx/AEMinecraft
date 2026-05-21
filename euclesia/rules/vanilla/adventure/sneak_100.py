from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def sneak_100(helper: RuleHelper) -> dict:
    return {
        A_SNEAK_100: helper.all_of()
    }
