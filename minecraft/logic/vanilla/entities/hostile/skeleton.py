from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def skeleton(helper: RuleHelper) -> dict:
    return {
        E_SKELETON: helper.entity(E_SKELETON)
    }
