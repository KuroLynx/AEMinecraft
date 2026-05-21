from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def cow(helper: RuleHelper) -> dict:
    return {
        E_COW: helper.entity(E_COW)
    }
