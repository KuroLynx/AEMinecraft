from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def surge_protector(helper: RuleHelper) -> dict:
    return {
        A_SURGE_PROTECTOR: helper.all_of(helper.material(MAT_COPPER), helper.can_trade(False, 0))
    }
