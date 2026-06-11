from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def spider(helper: RuleHelper) -> dict:
    return {
        E_SPIDER: helper.entity(E_SPIDER)
    }
