from ...constants import *
from ...helpers import RuleHelper


def total_beelocation(helper: RuleHelper) -> dict:
    return {
        A_TOTAL_BEELOCATION: helper.all_of(
            helper.entity(E_BEE),
            helper.knowledge(K_ENCHANT),
            helper.any_of(
                helper.knowledge(K_SHOVEL),
                helper.knowledge(K_PICKAXE),
                helper.knowledge(K_AXE),
                helper.knowledge(K_HOE),
            )
        )
    }
