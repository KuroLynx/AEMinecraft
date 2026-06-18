from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def ender_dragon(helper: RuleHelper) -> dict:
    return {
        E_ENDER_DRAGON: helper.all_of(
            helper.entity(E_ENDER_DRAGON),
            helper.knowledge(K_BOW),  # Sharpshooter: shoot out the end crystals
            helper.any_of(
                helper.can_kill(),
                helper.can_get_bed(),  # bed-bombing strategy
            ),
        )
    }
