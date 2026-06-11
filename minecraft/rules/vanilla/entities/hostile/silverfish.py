from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def silverfish(helper: RuleHelper) -> dict:
    return {
        E_SILVERFISH: helper.entity(E_SILVERFISH)
    }
