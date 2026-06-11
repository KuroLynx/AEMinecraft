from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def cave_spider(helper: RuleHelper) -> dict:
    return {
        E_CAVE_SPIDER: helper.entity(E_CAVE_SPIDER)
    }
