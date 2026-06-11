from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def zombie(helper: RuleHelper) -> dict:
    return {
        E_ZOMBIE: helper.entity(E_ZOMBIE)
    }
