from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def parched(helper: RuleHelper) -> dict:
    return {
        E_PARCHED: helper.entity(E_PARCHED)
    }
