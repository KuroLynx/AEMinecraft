from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def with_our_powers_combined(helper: RuleHelper) -> dict:
    return {
        A_WITH_OUR_POWERS_COMBINED: helper.all_of(
            helper.entity(E_FROG),
            helper.entity(E_MAGMA_CUBE),
        ),
    }
