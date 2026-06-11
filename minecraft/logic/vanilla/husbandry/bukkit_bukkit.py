from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def bukkit_bukkit(helper: RuleHelper) -> dict:
    return {
        A_BUKKIT_BUKKIT: helper.all_of(
            helper.entity(E_TADPOLE),
            helper.entity(E_FROG),
            helper.can_craft_bucket(),
        ),
    }
