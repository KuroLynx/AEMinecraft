from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def bat(helper: RuleHelper) -> dict:
    return {
        E_BAT: helper.entity(E_BAT)
    }
