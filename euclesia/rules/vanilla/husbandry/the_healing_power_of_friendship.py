from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def the_healing_power_of_friendship(helper: RuleHelper) -> dict:
    return {
        A_THE_HEALING_POWER_OF_FRIENDSHIP: helper.all_of(
            helper.entity("Axolotl"),
            helper.has_any_entities(E_DROWNED, "Guardian", "Elder Guardian"),
        ),
    }
