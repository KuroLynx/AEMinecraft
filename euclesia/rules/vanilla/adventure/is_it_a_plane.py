from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def is_it_a_plane(helper: RuleHelper) -> dict:
    return {
        A_IS_IT_A_PLANE: helper.all_of(
            helper.can_get_spyglass(),
            helper.entity("Ender Dragon"),
        )
    }
