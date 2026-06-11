from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def caves_and_cliffs(helper: RuleHelper) -> dict:
    return {
        A_CAVES_AND_CLIFFS: helper.any_of(
            helper.can_craft_bucket(),
            helper.can_get_totem(),
            helper.all_of(
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_LOCAL_BREWERY}"),
                helper.entity(E_PHANTOM)
            )
        )
    }
