from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def happy_ghast(helper: RuleHelper) -> dict:
    return {
        E_HAPPY_GHAST: helper.entity(E_HAPPY_GHAST)
    }
