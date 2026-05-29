from ...constants import *
from ...helpers import RuleHelper


def remote_getaway(helper: RuleHelper) -> dict:
    return {
        A_REMOTE_GETAWAY: helper.reached(f"{ADVANCEMENT_PREFIX}{A_FREE_THE_END}")
    }
