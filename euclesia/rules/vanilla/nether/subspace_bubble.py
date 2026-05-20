from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def subspace_bubble(helper: RuleHelper) -> dict:
    return {
        A_SUBSPACE_BUBBLE: helper.all_of()
    }
