from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def squid(helper: RuleHelper) -> dict:
    return {
        E_SQUID: helper.entity(E_SQUID)
    }
