from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def getting_an_upgrade(helper: RuleHelper) -> dict:
    return {
        A_GETTING_AN_UPGRADE: helper.all_of(
            helper.any_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_STONE_AGE}"),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),
                helper.can_trade_villager(),  # A toolsmith
            ),
            helper.knowledge(K_PICKAXE),
        ),
    }
