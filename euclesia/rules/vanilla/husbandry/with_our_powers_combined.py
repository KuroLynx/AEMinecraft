from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def with_our_powers_combined(helper: RuleHelper) -> dict:
    return {
        A_WITH_OUR_POWERS_COMBINED: helper.all_of(
            helper.entity("Frog"),
            helper.entity("Magma Cube"),
        ),
    }
