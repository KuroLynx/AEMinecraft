from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def witch(helper: RuleHelper) -> dict:
    return {
        E_WITCH: helper.entity(E_WITCH)
    }
