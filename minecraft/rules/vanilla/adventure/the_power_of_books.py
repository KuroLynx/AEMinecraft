from ...constants import *
from ...helpers import RuleHelper


def the_power_of_books(helper: RuleHelper) -> dict:
    return {
        A_THE_POWER_OF_BOOKS: helper.any_of(
            helper.all_of(
                helper.material(MAT_IRON),  # mine Redstone
                helper.knowledge(K_PICKAXE),
                helper.access_region(REGION_NETHER),  # Nether Quartz
            ),
            helper.structure(S_ANCIENT_CITY),  # generate here
        )
    }
