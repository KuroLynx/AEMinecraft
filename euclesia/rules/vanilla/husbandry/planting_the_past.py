from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def planting_the_past(helper: RuleHelper) -> dict:
    return {
        A_PLANTING_THE_PAST: helper.reached(f"{ADVANCEMENT_PREFIX}Smells Interesting")
    }
