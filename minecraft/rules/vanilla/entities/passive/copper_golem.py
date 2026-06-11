from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def copper_golem(helper: RuleHelper) -> dict:
    return {
        E_COPPER_GOLEM: helper.entity(E_COPPER_GOLEM)
    }
