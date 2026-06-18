from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def breeze(helper: RuleHelper) -> dict:
    return {
        E_BREEZE: helper.entity(E_BREEZE)
    }
