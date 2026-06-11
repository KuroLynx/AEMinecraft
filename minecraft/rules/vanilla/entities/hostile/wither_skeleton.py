from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def wither_skeleton(helper: RuleHelper) -> dict:
    return {
        E_WITHER_SKELETON: helper.entity(E_WITHER_SKELETON)
    }
