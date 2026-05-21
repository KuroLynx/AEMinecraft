from worlds.euclesia.data import MOBS_ALL, MOBS_BOSS
from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def mob_kabob(helper: RuleHelper) -> dict:
    return {
        A_MOB_KABOB: helper.all_of(
            helper.knowledge("Spear Handling"),
            helper.has_any_entities(*[name for name in MOBS_ALL.keys() if name not in MOBS_BOSS]),
        )
    }
