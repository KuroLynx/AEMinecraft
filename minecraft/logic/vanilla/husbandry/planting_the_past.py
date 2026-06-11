from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def planting_the_past(helper: RuleHelper) -> dict:
    return {
        A_PLANTING_THE_PAST: helper.reached(f"{ADVANCEMENT_PREFIX}{A_SMELLS_INTERESTING}")
    }
