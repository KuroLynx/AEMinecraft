from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def fishy_business(helper: RuleHelper) -> dict:
    return {
        A_FISHY_BUSINESS: helper.knowledge(K_FISHING)
    }
