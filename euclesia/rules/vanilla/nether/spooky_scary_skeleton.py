from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def spooky_scary_skeleton(helper: RuleHelper) -> dict:
    return {
        A_SPOOKY_SCARY_SKELETON: helper.all_of(
            helper.entity("Wither Skeleton"),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),
        ),
    }
