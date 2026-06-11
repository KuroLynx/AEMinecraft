from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def iron_golem(helper: RuleHelper) -> dict:
    return {
        E_IRON_GOLEM: helper.entity(E_IRON_GOLEM)
    }
