from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def blowback(helper: RuleHelper) -> dict:
    return {
        A_BLOWBACK: helper.entity(E_BREEZE)
    }
