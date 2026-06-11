from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def wandering_trader(helper: RuleHelper) -> dict:
    return {
        E_WANDERING_TRADER: helper.entity(E_WANDERING_TRADER)
    }
