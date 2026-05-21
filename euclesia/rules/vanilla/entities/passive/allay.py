from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def allay(helper: RuleHelper) -> dict:
    return {
        E_ALLAY: helper.entity(E_ALLAY),
    }
