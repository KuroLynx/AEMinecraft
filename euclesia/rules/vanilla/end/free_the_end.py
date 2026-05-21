from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def free_the_end(helper: RuleHelper) -> dict:
    return {
        A_FREE_THE_END: helper.entity("Ender Dragon")
    }
