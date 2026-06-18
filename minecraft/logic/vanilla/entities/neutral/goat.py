from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def goat(helper: RuleHelper) -> dict:
    return {
        E_GOAT: helper.entity(E_GOAT)
    }
