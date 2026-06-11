from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def piglin_brute(helper: RuleHelper) -> dict:
    return {
        E_PIGLIN_BRUTE: helper.entity(E_PIGLIN_BRUTE)
    }
