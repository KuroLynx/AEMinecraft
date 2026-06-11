from .. import *  # constants, RuleHelper, mob sets (re-export hub)

def hidden_in_the_depths(helper: RuleHelper) -> dict:
    return {
        A_HIDDEN_IN_THE_DEPTHS: helper.all_of(
            helper.material(MAT_NETHERITE),  # Always needed
            helper.any_of(
                helper.knowledge(K_PICKAXE),  # Obtain it
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),
            ),
        ),
    }
