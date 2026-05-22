from ...constants import *
from ...helpers import RuleHelper


def star_trader(helper: RuleHelper) -> dict:
    return {
        A_STAR_TRADER: helper.can_trade()
    }
