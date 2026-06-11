from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def chicken(helper: RuleHelper) -> dict:
    return {
        E_CHICKEN: helper.entity(E_CHICKEN)
    }
