from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def you_need_a_mint(helper: RuleHelper) -> dict:
    return {
        A_YOU_NEED_A_MINT: helper.entity(E_ENDER_DRAGON)
    }
