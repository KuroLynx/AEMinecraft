from ....data import MOBS_TAMEABLE
from ...constants import *
from ...helpers import RuleHelper


def best_friends_forever(helper: RuleHelper) -> dict:
    return {
        A_BEST_FRIENDS_FOREVER: helper.any_of(*[helper.can_tame(mob) for mob in MOBS_TAMEABLE.keys()])
    }
