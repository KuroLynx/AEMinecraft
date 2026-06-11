from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def spooky_scary_skeleton(helper: RuleHelper) -> dict:
    return {
        A_SPOOKY_SCARY_SKELETON: helper.entity(E_WITHER_SKELETON),
    }
