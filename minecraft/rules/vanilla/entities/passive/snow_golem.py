from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def snow_golem(helper: RuleHelper) -> dict:
    return {
        E_SNOW_GOLEM: helper.entity(E_SNOW_GOLEM)
    }
