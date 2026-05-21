from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def little_sniffs(helper: RuleHelper) -> dict:
    return {
        A_LITTLE_SNIFFS: helper.reached(f"{ADVANCEMENT_PREFIX}Smells Interesting")
    }
