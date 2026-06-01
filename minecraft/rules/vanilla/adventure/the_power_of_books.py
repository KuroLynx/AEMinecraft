from ...constants import *
from ...helpers import RuleHelper


def the_power_of_books(helper: RuleHelper) -> dict:
    return {
        A_THE_POWER_OF_BOOKS: helper.any_of(
            helper.all_of(
                helper.material(MAT_IRON),  # mine Redstone
                helper.knowledge(K_PICKAXE),
                helper.access_region(REGION_NETHER),  # Nether Quartz
                # Need books to store on the chiseled bookshelf. In the Nether the only book source
                # is a Bastion chest, so gating on it keeps this branch honest when structures are
                # locked; the rest are Overworld sources for a player coming the other way.
                helper.any_of(
                    helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Bastion chest (Nether)
                    helper.can_trade_villager(),  # Librarian
                    helper.any_mineshaft(),       # chest
                    helper.structure(S_DUNGEON),  # chest
                    helper.structure(S_MANSION),  # chest
                ),
            ),
            helper.structure(S_ANCIENT_CITY),  # generate here
        )
    }
