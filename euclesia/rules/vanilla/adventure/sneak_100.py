from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def sneak_100(helper: RuleHelper) -> dict:
    return {
        A_SNEAK_100: helper.all_of()
    }
