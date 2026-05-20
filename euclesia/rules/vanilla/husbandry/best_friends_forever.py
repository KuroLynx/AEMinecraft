from euclesia import MOBS_TAMEABLE
from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def best_friends_forever(helper: RuleHelper) -> dict:
    return {
        A_BEST_FRIENDS_FOREVER: helper.has_any_entities(*MOBS_TAMEABLE.keys())
    }
