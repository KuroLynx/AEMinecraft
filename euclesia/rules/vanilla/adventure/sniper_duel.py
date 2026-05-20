from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def sniper_duel(helper: RuleHelper) -> dict:
    return {
        A_SNIPER_DUEL: helper.all_of(
            helper.knowledge("Sharpshooter"),
            helper.can_get_arrow(),
            helper.entity(E_SKELETON),
        )
    }
