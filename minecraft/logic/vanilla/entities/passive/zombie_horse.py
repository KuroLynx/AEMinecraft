from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def zombie_horse(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE_HORSE: helper.entity(E_ZOMBIE_HORSE)
    }
