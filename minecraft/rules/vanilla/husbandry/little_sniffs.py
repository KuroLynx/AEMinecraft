from ...constants import *
from ...helpers import RuleHelper


def little_sniffs(helper: RuleHelper) -> dict:
    return {
        A_LITTLE_SNIFFS: helper.reached(f"{ADVANCEMENT_PREFIX}{A_SMELLS_INTERESTING}")
    }
