from worlds.euclesia.data import MOBS_BREEDABLE
from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper

def the_parrots_and_the_bats(helper: RuleHelper) -> dict:
    return {
        A_THE_PARROTS_AND_THE_BATS: helper.any_of(
            helper.entity("Trader Llama"),
            helper.has_any_entities(*MOBS_BREEDABLE.keys()),
        ),
    }
