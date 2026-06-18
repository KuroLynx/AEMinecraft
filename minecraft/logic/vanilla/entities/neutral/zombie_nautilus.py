from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def zombie_nautilus(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE_NAUTILUS: helper.entity(E_ZOMBIE_NAUTILUS)
    }
