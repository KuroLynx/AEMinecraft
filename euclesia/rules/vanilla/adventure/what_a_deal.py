from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def what_a_deal(helper: RuleHelper) -> dict:
    return {
        A_WHAT_A_DEAL: helper.can_trade()
    }
