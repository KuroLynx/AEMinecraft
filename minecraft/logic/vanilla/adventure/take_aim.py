from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def take_aim(helper: RuleHelper) -> dict:
    return {
        A_TAKE_AIM: helper.all_of(
            helper.knowledge(K_BOW),
            helper.can_get_arrow()
        )
    }
