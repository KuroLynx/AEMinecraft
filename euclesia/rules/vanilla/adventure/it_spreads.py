from worlds.euclesia.data import MOBS_ALL
from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def it_spreads(helper: RuleHelper) -> dict:
    return {
        A_IT_SPREADS: helper.has_any_entities(*MOBS_ALL.keys())
    }
