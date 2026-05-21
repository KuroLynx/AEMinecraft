from worlds.euclesia.rules.constants import *
from worlds.euclesia.rules.helpers import RuleHelper


def ice_bucket_challenge(helper: RuleHelper) -> dict:
    return {
        A_ICE_BUCKET_CHALLENGE: helper.any_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_DIAMONDS}"),  # Mine it with your diamonds
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnants
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # In Nether Fortress
            helper.any_portal(True),  # In any ruined portal
            helper.can_barter(),  # Piglin trade
            helper.any_village(),  # In village chests
        ),
    }
