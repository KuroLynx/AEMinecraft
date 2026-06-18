from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def voluntary_exile(helper: RuleHelper) -> dict:
    return {
        A_VOLUNTARY_EXILE: helper.entity(E_PILLAGER)
    }
