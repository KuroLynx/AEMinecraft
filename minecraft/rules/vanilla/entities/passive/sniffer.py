from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def sniffer(helper: RuleHelper) -> dict:
    return {
        E_SNIFFER: helper.entity(E_SNIFFER)
    }
