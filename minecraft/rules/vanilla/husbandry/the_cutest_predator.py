from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def the_cutest_predator(helper: RuleHelper) -> dict:
    return {
        A_THE_CUTEST_PREDATOR: helper.all_of(
            helper.entity(E_AXOLOTL),
            helper.can_craft_bucket(),
        ),
    }
