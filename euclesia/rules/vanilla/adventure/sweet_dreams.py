from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def sweet_dreams(helper: RuleHelper) -> dict:
    return {
        A_SWEET_DREAMS: helper.any_of(
            helper.can_trade(False, 2),  # Shepherd
            helper.can_get_string(),
            helper.entity("Sheep"),
            helper.structure(S_IGLOO),
            helper.structure(S_MANSION)
        )
    }
