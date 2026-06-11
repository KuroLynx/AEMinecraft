from .. import *  # constants, RuleHelper, mob sets (re-export hub)



def camel(helper: RuleHelper) -> dict:
    return {
        E_CAMEL: helper.entity(E_CAMEL)
    }
