from euclesia import MOBS_ALL
from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def it_spreads(helper: RuleHelper) -> dict:
    return {
        A_IT_SPREADS: helper.has_any_entities(*MOBS_ALL.keys())
    }
