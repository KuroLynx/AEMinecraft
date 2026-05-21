from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def a_complete_catalogue(helper: RuleHelper) -> dict:
    return {
        A_A_COMPLETE_CATALOGUE: helper.entity(E_CAT)
    }
