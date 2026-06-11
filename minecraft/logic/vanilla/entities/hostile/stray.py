from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def stray(helper: RuleHelper) -> dict:
    return {
        E_STRAY: helper.entity(E_STRAY)
    }
