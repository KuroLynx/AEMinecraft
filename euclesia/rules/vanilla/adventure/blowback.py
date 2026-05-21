from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def blowback(helper: RuleHelper) -> dict:
    return {
        A_BLOWBACK: helper.entity("Breeze")
    }
