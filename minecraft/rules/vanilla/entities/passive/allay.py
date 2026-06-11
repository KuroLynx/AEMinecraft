from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def allay(helper: RuleHelper) -> dict:
    return {
        E_ALLAY: helper.entity(E_ALLAY),
    }
