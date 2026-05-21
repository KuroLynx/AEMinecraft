from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def chicken(helper: RuleHelper) -> dict:
    return {
        E_CHICKEN: helper.entity(E_CHICKEN)
    }
