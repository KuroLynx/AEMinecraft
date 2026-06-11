from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def glow_squid(helper: RuleHelper) -> dict:
    return {
        E_GLOW_SQUID: helper.entity(E_GLOW_SQUID)
    }
