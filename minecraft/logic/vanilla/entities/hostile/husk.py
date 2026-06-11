from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def husk(helper: RuleHelper) -> dict:
    return {
        E_HUSK: helper.entity(E_HUSK)
    }
