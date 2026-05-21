from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def enchanter(helper: RuleHelper) -> dict:
    return {
        A_ENCHANTER: helper.all_of(
        helper.knowledge(K_ENCHANT),
        helper.reached(f"{ADVANCEMENT_PREFIX}{A_ICE_BUCKET_CHALLENGE}"),
        helper.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),  # In case we don't get obsidian by mining
        # Book: (Make assumption that we have a grindstone for enchanted book)
        helper.any_of(
            helper.entity(E_COW),
            helper.can_trade(False),  # bookshelf from librarian
            helper.any_mineshaft(),
            helper.structure(S_ANCIENT_CITY),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
            helper.structure(S_DESERT_PYRAMID),
            helper.structure(S_JUNGLE_PYRAMID),
            helper.structure(S_PILLAGER_OUTPOST),
            helper.structure(S_SHIPWRECK),
            helper.structure(S_DUNGEON),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold
            helper.structure(S_OCEAN_RUIN_COLD),
            helper.structure(S_OCEAN_RUIN_WARM),
            helper.structure(S_MANSION),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # In Trial Chambers (Vault)
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # In Trial Chambers (Ominous Vault)
            helper.can_barter(),
            helper.knowledge(K_FISHING),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_HERO_OF_THE_VILLAGE}"),
        ),
    ),
    }
