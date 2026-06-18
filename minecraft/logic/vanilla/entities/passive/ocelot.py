from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def ocelot(helper: RuleHelper) -> dict:
    return {
        E_OCELOT: helper.entity(E_OCELOT)
    }
