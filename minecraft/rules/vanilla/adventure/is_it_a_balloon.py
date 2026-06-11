from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def is_it_a_balloon(helper: RuleHelper) -> dict:
    return {
        A_IS_IT_A_BALLOON: helper.all_of(
            helper.entity(E_GHAST),
            helper.can_get_spyglass()
        )
    }
