from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def glow_and_behold(helper: RuleHelper) -> dict:
    return {
        A_GLOW_AND_BEHOLD: helper.entity("Glow Squid")
    }
