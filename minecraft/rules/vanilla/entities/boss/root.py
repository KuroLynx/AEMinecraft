from ....helpers import RuleHelper
from .elder_guardian import elder_guardian
from .ender_dragon import ender_dragon
from .warden import warden
from .wither import wither


def get_boss_rules(helper: RuleHelper) -> dict:
    return (
        elder_guardian(helper) |
        ender_dragon(helper) |
        warden(helper) |
        wither(helper)
    )
