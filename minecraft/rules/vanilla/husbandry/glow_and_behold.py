from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def glow_and_behold(helper: RuleHelper) -> dict:
    return {
        A_GLOW_AND_BEHOLD: helper.entity(E_GLOW_SQUID)
    }
