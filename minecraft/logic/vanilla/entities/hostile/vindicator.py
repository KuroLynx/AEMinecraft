from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def vindicator(helper: RuleHelper) -> dict:
    return {
        E_VINDICATOR: helper.entity(E_VINDICATOR)
    }
