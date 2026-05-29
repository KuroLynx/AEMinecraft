from ....data import MOBS_ALL
from ...constants import *
from ...helpers import RuleHelper


def it_spreads(helper: RuleHelper) -> dict:
    return {
        A_IT_SPREADS: helper.has_any_entities(*MOBS_ALL.keys())
    }
