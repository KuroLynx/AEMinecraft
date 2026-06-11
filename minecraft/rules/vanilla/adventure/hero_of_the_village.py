from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def hero_of_the_village(helper: RuleHelper) -> dict:
    return {
        A_HERO_OF_THE_VILLAGE: helper.all_of(
            helper.can_trade_villager(0),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_VOLUNTARY_EXILE}"),
        )
    }
