from ...constants import *
from ...helpers import RuleHelper


def is_it_a_balloon(helper: RuleHelper) -> dict:
    return {
        A_IS_IT_A_BALLOON: helper.all_of(
            helper.entity(E_GHAST),
            helper.can_get_spyglass()
        )
    }
