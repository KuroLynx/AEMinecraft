from ...constants import *
from ...helpers import RuleHelper


def a_complete_catalogue(helper: RuleHelper) -> dict:
    return {
        A_A_COMPLETE_CATALOGUE: helper.can_tame(E_CAT)
    }
