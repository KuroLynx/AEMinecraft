from ...constants import *
from ...helpers import RuleHelper


def planting_the_past(helper: RuleHelper) -> dict:
    return {
        A_PLANTING_THE_PAST: helper.reached(f"{ADVANCEMENT_PREFIX}Smells Interesting")
    }
