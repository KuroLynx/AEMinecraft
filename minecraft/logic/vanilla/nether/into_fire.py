from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def into_fire(helper: RuleHelper) -> dict:
    return {
        A_INTO_FIRE: helper.entity(E_BLAZE),
    }
