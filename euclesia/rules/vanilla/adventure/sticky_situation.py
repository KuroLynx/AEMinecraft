from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def sticky_situation(helper: RuleHelper) -> dict:
    return {
        A_STICKY_SITUATION: helper.any_of(
            helper.entity("Bee"),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}")
        )
    }
