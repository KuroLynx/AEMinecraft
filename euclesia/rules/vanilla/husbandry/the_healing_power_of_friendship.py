from ...constants import *
from ...helpers import RuleHelper


def the_healing_power_of_friendship(helper: RuleHelper) -> dict:
    return {
        A_THE_HEALING_POWER_OF_FRIENDSHIP: helper.all_of(
            helper.entity(E_AXOLOTL),
            helper.has_any_entities(E_DROWNED, E_GUARDIAN, E_ELDER_GUARDIAN),
        ),
    }
