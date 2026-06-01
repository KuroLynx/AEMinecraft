from ....data import MOBS_BREEDABLE
from ...constants import *
from ...helpers import RuleHelper

def the_parrots_and_the_bats(helper: RuleHelper) -> dict:
    return {
        A_THE_PARROTS_AND_THE_BATS: helper.any_of(
            helper.entity(E_TRADER_LLAMA),
            *[helper.can_breed(mob) for mob in MOBS_BREEDABLE.keys()],
        ),
    }
