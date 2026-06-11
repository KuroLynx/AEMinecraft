from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def who_is_cutting_onions(helper: RuleHelper) -> dict:
    return {
        A_WHO_IS_CUTTING_ONIONS: helper.any_of(
            helper.can_barter(),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),
        ),
    }
