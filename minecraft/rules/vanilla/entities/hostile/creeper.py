from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def creeper(helper: RuleHelper) -> dict:
    return {
        E_CREEPER: helper.entity(E_CREEPER)
    }
