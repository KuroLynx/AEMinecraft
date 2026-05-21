from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def you_need_a_mint(helper: RuleHelper) -> dict:
    return {
        A_YOU_NEED_A_MINT: helper.entity("Ender Dragon")
    }
