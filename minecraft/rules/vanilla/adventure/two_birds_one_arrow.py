from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def two_birds_one_arrow(helper: RuleHelper) -> dict:
    return {
        A_TWO_BIRDS_ONE_ARROW: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_OL_BETSY}"),
            helper.knowledge(K_ENCHANT),
            helper.entity(E_PHANTOM),
        )
    }
