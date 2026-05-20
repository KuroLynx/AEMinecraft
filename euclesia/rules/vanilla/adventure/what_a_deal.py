from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def what_a_deal(helper: RuleHelper) -> dict:
    return {
        A_WHAT_A_DEAL: helper.can_trade()
    }
