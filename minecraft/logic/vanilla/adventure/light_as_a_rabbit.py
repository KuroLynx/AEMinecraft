from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def light_as_a_rabbit(helper: RuleHelper) -> dict:
    return {
        A_LIGHT_AS_A_RABBIT: helper.all_of(
            helper.knowledge(K_ARMOR),
            helper.needs_biome_finder(),  # powder snow only in cold mountain biomes
            helper.any_of(
                # Leather sources → craft boots
                helper.has_any_entities(E_COW, E_DONKEY, E_HORSE, E_LLAMA, E_MOOSHROOM, E_MULE, E_TRADER_LLAMA, E_HOGLIN),
                helper.entity(E_RABBIT),  # 4 Rabbit Hide → Leather
                helper.knowledge(K_FISHING),  # fishing junk
                helper.can_barter(),  # Piglin bartering
                helper.can_trade_villager(2),  # leatherworker trade
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),  # leatherworker gift
                helper.structure(S_ANCIENT_CITY),  # Leather in chest
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Leather in Bastion
                helper.structure(S_DESERT_PYRAMID),  # Leather in chest
                helper.structure(S_JUNGLE_PYRAMID),  # Leather in chest
                helper.structure(S_DUNGEON),  # Leather in chest
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Leather in Stronghold
                helper.any_village(),  # Leather in tannery
                # Leather Boots directly
                helper.any_shipwreck(),  # Leather Boots in supply chest
            )
        )
    }
