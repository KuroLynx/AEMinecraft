from ....data import MOBS_TAMEABLE
from ...constants import *
from ...helpers import RuleHelper


def best_friends_forever(helper: RuleHelper) -> dict:
    return {
        A_BEST_FRIENDS_FOREVER: helper.has_any_entities(*MOBS_TAMEABLE.keys())
    }
