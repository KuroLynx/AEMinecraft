from ...constants import *
from ...helpers import RuleHelper


def free_the_end(helper: RuleHelper) -> dict:
    return {
        A_FREE_THE_END: helper.reached(f"{BOSS_KILL_PREFIX}{E_ENDER_DRAGON}")
    }
