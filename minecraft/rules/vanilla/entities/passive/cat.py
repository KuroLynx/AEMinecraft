from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def cat(helper: RuleHelper) -> dict:
    return {
        E_CAT: helper.entity(E_CAT)
    }
