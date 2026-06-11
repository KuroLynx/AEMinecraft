from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def armadillo(helper: RuleHelper) -> dict:
    return {
        E_ARMADILLO: helper.entity(E_ARMADILLO)
    }
