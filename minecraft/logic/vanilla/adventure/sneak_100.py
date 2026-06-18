from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def sneak_100(helper: RuleHelper) -> dict:
    return {
        A_SNEAK_100: helper.all_of(
            helper.knowledge(K_PICKAXE)
        )
    }
