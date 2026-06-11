from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def skeleton_horse(helper: RuleHelper) -> dict:
    return {
        E_SKELETON_HORSE: helper.entity(E_SKELETON_HORSE)
    }
