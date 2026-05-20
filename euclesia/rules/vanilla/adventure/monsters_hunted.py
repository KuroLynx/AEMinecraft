from euclesia import MOBS_HOSTILE
from euclesia.rules.constants import *
from euclesia.rules.helpers import RuleHelper


def monsters_hunted(helper: RuleHelper) -> dict:
    return {
        A_MONSTERS_HUNTED: helper.all_of(
            helper.has_all_entities(*[n for n in MOBS_HOSTILE.keys() if n != "Warden"]),
            helper.entity("Ender Dragon"),
            helper.entity("Wither"),
        ),
    }
