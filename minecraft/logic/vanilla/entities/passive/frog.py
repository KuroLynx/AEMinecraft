from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def frog(helper: RuleHelper) -> dict:
    return {
        E_FROG: helper.entity(E_FROG)
    }
