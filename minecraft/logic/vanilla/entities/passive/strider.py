from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def strider(helper: RuleHelper) -> dict:
    return {
        E_STRIDER: helper.entity(E_STRIDER)
    }
