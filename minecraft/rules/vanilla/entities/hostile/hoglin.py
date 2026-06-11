from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def hoglin(helper: RuleHelper) -> dict:
    return {
        E_HOGLIN: helper.entity(E_HOGLIN)
    }
