from ...constants import *
from ...helpers import RuleHelper


def blowback(helper: RuleHelper) -> dict:
    return {
        A_BLOWBACK: helper.entity(E_BREEZE)
    }
