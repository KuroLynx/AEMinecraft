from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def salmon(helper: RuleHelper) -> dict:
    return {
        E_SALMON: helper.entity(E_SALMON)
    }
