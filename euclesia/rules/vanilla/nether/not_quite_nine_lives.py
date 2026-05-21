from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def not_quite_nine_lives(helper: RuleHelper) -> dict:
    return {
        A_NOT_QUITE_NINE_LIVES: helper.reached(f"{ADVANCEMENT_PREFIX}Who is Cutting Onions?")
    }
