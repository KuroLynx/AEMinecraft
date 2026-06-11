from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def isnt_it_scute(helper: RuleHelper) -> dict:
    return {
        A_ISNT_IT_SCUTE: helper.all_of(helper.entity(E_ARMADILLO), helper.has_brush())
    }
