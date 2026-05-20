from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def a_complete_catalogue(helper: RuleHelper) -> dict:
    return {
        A_A_COMPLETE_CATALOGUE: helper.entity(E_CAT)
    }
