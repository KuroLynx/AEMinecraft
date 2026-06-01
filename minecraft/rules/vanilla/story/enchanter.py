from ...constants import *
from ...helpers import RuleHelper

def enchanter(helper: RuleHelper) -> dict:
    return {
        A_ENCHANTER: helper.all_of(
        helper.knowledge(K_ENCHANT),
        # Enchanting table = obsidian + diamonds + book. Use the region-aware obsidian rule rather
        # than the Overworld-only Ice Bucket Challenge (water-on-lava) advancement, so a Nether
        # player who sources obsidian from a bastion/fortress/portal/barter can still enchant.
        helper.can_get_obsidian(),
        helper.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),  # diamonds for the table
        # Book: (Make assumption that we have a grindstone for enchanted book)
        helper.any_of(
            helper.entity(E_COW),
            helper.can_trade_villager(),  # bookshelf from librarian
            helper.any_mineshaft(),
            helper.structure(S_ANCIENT_CITY),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
            helper.structure(S_DESERT_PYRAMID),
            helper.structure(S_JUNGLE_PYRAMID),
            helper.structure(S_PILLAGER_OUTPOST),
            helper.any_shipwreck(),
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
