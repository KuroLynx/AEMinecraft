from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def is_it_a_plane(helper: RuleHelper) -> dict:
    return {
        A_IS_IT_A_PLANE: helper.all_of(
            helper.can_get_spyglass(),
            helper.entity(E_ENDER_DRAGON),
        )
    }
