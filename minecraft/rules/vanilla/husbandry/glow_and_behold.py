from ...constants import *
from ...helpers import RuleHelper


def glow_and_behold(helper: RuleHelper) -> dict:
    return {
        A_GLOW_AND_BEHOLD: helper.entity(E_GLOW_SQUID)
    }
