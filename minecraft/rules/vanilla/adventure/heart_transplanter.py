from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def heart_transplanter(helper: RuleHelper) -> dict:
    return {
        A_HEART_TRANSPLANTER: helper.entity(E_CREAKING)
    }
