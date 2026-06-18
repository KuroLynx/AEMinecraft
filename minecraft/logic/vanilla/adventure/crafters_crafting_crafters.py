from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def crafters_crafting_crafters(helper: RuleHelper) -> dict:
    return {
        A_CRAFTERS_CRAFTING_CRAFTERS: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_ACQUIRE_HARDWARE}"),
            helper.can_get_redstone(),
        )
    }
