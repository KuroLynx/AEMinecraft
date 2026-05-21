from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def bukkit_bukkit(helper: RuleHelper) -> dict:
    return {
        A_BUKKIT_BUKKIT: helper.all_of(
            helper.entity("Tadpole"),
            helper.entity("Frog"),
            helper.can_craft_bucket(),
        ),
    }
