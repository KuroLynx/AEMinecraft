from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def cat(helper: RuleHelper) -> dict:
    return {
        E_CAT: helper.entity(E_CAT)
    }
