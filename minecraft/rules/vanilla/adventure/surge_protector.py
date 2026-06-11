from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def surge_protector(helper: RuleHelper) -> dict:
    return {
        A_SURGE_PROTECTOR: helper.all_of(helper.material(MAT_COPPER), helper.can_trade_villager(0))
    }
