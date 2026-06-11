from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def withering_heights(helper: RuleHelper) -> dict:
    return {
        A_WITHERING_HEIGHTS: helper.reached(f"{BOSS_KILL_PREFIX}{E_WITHER}")
    }
