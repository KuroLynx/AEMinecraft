from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def who_is_cutting_onions(helper: RuleHelper) -> dict:
    return {
        A_WHO_IS_CUTTING_ONIONS: helper.any_of(
            helper.can_barter(),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),
        ),
    }
