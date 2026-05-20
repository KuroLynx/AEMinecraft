from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def not_quite_nine_lives(helper: RuleHelper) -> dict:
    return {
        A_NOT_QUITE_NINE_LIVES: helper.reached(f"{ADVANCEMENT_PREFIX}Who is Cutting Onions?")
    }
