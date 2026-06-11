from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def little_sniffs(helper: RuleHelper) -> dict:
    return {
        A_LITTLE_SNIFFS: helper.reached(f"{ADVANCEMENT_PREFIX}{A_SMELLS_INTERESTING}")
    }
