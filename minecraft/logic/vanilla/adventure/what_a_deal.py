from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def what_a_deal(helper: RuleHelper) -> dict:
    return {
        A_WHAT_A_DEAL: helper.can_trade()
    }
