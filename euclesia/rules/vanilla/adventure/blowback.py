from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def blowback(helper: RuleHelper) -> dict:
    return {
        A_BLOWBACK: helper.entity("Breeze")
    }
