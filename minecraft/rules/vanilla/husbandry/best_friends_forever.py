from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def best_friends_forever(helper: RuleHelper) -> dict:
    return {
        A_BEST_FRIENDS_FOREVER: helper.any_of(*[helper.can_tame(mob) for mob in MOBS_TAMEABLE.keys()])
    }
