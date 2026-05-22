from ...constants import *
from ...helpers import RuleHelper


def light_as_a_rabbit(helper: RuleHelper) -> dict:
    return {
        A_LIGHT_AS_A_RABBIT: helper.all_of(
            helper.knowledge(K_ARMOR),
            helper.any_of(
                # Leather sources → craft boots
                helper.has_any_entities(E_COW, "Donkey", "Horse", "Llama", "Mooshroom", "Mule", "Trader Llama", "Hoglin"),
                helper.entity("Rabbit"),  # 4 Rabbit Hide → Leather
                helper.knowledge(K_FISHING),  # fishing junk
                helper.can_barter(),  # Piglin bartering
                helper.can_trade(False, 2),  # leatherworker trade
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),  # leatherworker gift
                helper.structure(S_ANCIENT_CITY),  # Leather in chest
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Leather in Bastion
                helper.structure(S_DESERT_PYRAMID),  # Leather in chest
                helper.structure(S_JUNGLE_PYRAMID),  # Leather in chest
                helper.structure(S_DUNGEON),  # Leather in chest
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Leather in Stronghold
                helper.any_village(),  # Leather in tannery
                # Leather Boots directly
                helper.structure(S_SHIPWRECK),  # Leather Boots in supply chest
            )
        )
    }
