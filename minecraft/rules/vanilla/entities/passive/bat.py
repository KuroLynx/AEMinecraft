from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def bat(helper: RuleHelper) -> dict:
    return {
        E_BAT: helper.entity(E_BAT)
    }
