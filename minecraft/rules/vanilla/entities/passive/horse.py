from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def horse(helper: RuleHelper) -> dict:
    return {
        E_HORSE: helper.entity(E_HORSE)
    }
