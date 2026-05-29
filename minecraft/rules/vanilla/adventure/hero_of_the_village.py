from ...constants import *
from ...helpers import RuleHelper


def hero_of_the_village(helper: RuleHelper) -> dict:
    return {
        A_HERO_OF_THE_VILLAGE: helper.all_of(
            helper.can_trade(False, 0),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_VOLUNTARY_EXILE}"),
        )
    }
