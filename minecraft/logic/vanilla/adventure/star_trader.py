from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def star_trader(helper: RuleHelper) -> dict:
    return {
        A_STAR_TRADER: helper.can_trade()
    }
