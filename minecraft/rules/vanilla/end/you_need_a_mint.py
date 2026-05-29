from ...constants import *
from ...helpers import RuleHelper


def you_need_a_mint(helper: RuleHelper) -> dict:
    return {
        A_YOU_NEED_A_MINT: helper.entity(E_ENDER_DRAGON)
    }
