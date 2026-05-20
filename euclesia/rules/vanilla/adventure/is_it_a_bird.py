from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def is_it_a_bird(helper: RuleHelper) -> dict:
    return {
        A_IS_IT_A_BIRD: helper.all_of(
            helper.entity(E_PARROT),
            helper.can_get_spyglass()
        )
    }
