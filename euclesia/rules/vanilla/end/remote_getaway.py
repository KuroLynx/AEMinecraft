from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def remote_getaway(helper: RuleHelper) -> dict:
    return {
        A_REMOTE_GETAWAY: helper.reached(f"{ADVANCEMENT_PREFIX}Free the End")
    }
