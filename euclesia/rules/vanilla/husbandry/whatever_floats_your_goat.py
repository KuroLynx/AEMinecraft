from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def whatever_floats_your_goat(helper: RuleHelper) -> dict:
    return {
        A_WHATEVER_FLOATS_YOUR_GOAT: helper.entity("Goat")
    }
