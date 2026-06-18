from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def polar_bear(helper: RuleHelper) -> dict:
    return {
        E_POLAR_BEAR: helper.entity(E_POLAR_BEAR)
    }
