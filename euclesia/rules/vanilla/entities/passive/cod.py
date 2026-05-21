from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def cod(helper: RuleHelper) -> dict:
    return {
        E_COD: helper.entity(E_COD)
    }
