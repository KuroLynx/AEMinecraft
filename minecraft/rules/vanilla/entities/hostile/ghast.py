from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def ghast(helper: RuleHelper) -> dict:
    return {
        E_GHAST: helper.entity(E_GHAST)
    }
