from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def who_is_the_pillager_now(helper: RuleHelper) -> dict:
    return {
        A_WHO_IS_THE_PILLAGER_NOW: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_OL_BETSY}"),
            helper.entity(E_PILLAGER)
        )
    }
