from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def drowned(helper: RuleHelper) -> dict:
    return {
        E_DROWNED: helper.entity(E_DROWNED)
    }
