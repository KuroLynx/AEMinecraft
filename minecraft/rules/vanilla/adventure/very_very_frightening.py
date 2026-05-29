from ...constants import *
from ...helpers import RuleHelper


def very_very_frightening(helper: RuleHelper) -> dict:
    return {
        A_VERY_VERY_FRIGHTENING: helper.all_of(
            helper.can_get_trident(),
            helper.can_trade(False, 0),
            helper.knowledge(K_ENCHANT),
        )
    }
