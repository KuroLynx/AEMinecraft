from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper



def armadillo(helper: RuleHelper) -> dict:
    return {
        E_ARMADILLO: helper.entity(E_ARMADILLO)
    }
