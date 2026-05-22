from ...constants import *
from ...helpers import RuleHelper


def a_throwaway_joke(helper: RuleHelper) -> dict:
    return {
        A_A_THROWAWAY_JOKE: helper.can_get_trident()
    }
