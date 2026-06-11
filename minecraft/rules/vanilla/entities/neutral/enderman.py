from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def enderman(helper: RuleHelper) -> dict:
    return {
        E_ENDERMAN: helper.entity(E_ENDERMAN)
    }
