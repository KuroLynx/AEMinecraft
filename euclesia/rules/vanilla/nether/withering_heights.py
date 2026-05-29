from ...constants import *
from ...helpers import RuleHelper

def withering_heights(helper: RuleHelper) -> dict:
    return {
        A_WITHERING_HEIGHTS: helper.reached(f"{BOSS_KILL_PREFIX}{E_WITHER}")
    }
