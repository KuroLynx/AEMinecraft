from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def nautilus(helper: RuleHelper) -> dict:
    return {
        E_NAUTILUS: helper.entity(E_NAUTILUS)
    }
