from ....constants import *
from ....helpers import RuleHelper


def wither(helper: RuleHelper) -> dict:
    return {
        E_WITHER: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_SPOOKY_SCARY_SKELETON}"),  # skulls
            helper.entity(E_WITHER),
            helper.can_kill(),
            helper.knowledge(K_ARMOR),   # survive the blast / wither effect
            helper.material(MAT_IRON),   # at least iron-tier gear
        )
    }
