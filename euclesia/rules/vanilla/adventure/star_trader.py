from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def star_trader(helper: RuleHelper) -> dict:
    return {
        A_STAR_TRADER: helper.can_trade()
    }
