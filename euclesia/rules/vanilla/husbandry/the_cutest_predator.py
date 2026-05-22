from ...constants import *
from ...helpers import RuleHelper


def the_cutest_predator(helper: RuleHelper) -> dict:
    return {
        A_THE_CUTEST_PREDATOR: helper.all_of(
            helper.entity("Axolotl"),
            helper.can_craft_bucket(),
        ),
    }
