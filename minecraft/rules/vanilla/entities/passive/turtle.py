from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def turtle(helper: RuleHelper) -> dict:
    return {
        E_TURTLE: helper.entity(E_TURTLE)
    }
