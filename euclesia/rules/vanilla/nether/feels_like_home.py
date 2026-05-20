from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def feels_like_home(helper: RuleHelper) -> dict:
    return {
        A_FEELS_LIKE_HOME: helper.reached(f"{ADVANCEMENT_PREFIX}This Boat Has Legs")
    }
