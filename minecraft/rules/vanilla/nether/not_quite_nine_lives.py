from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def not_quite_nine_lives(helper: RuleHelper) -> dict:
    return {
        A_NOT_QUITE_NINE_LIVES: helper.reached(f"{ADVANCEMENT_PREFIX}{A_WHO_IS_CUTTING_ONIONS}")
    }
