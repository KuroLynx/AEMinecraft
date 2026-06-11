from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def cow(helper: RuleHelper) -> dict:
    return {
        E_COW: helper.entity(E_COW)
    }
