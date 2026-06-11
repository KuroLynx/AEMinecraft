from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def piglin(helper: RuleHelper) -> dict:
    return {
        E_PIGLIN: helper.entity(E_PIGLIN)
    }
