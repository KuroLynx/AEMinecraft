from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def zombified_piglin(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIFIED_PIGLIN: helper.entity(E_ZOMBIFIED_PIGLIN)
    }
