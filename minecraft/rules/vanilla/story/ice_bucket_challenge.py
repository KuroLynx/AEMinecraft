from ...constants import *
from ...helpers import RuleHelper


def ice_bucket_challenge(helper: RuleHelper) -> dict:
    return {
        # "Obtain obsidian" — reuse the shared, region-aware obsidian rule so this advancement and
        # the Nether→Overworld portal gate stay in sync.
        A_ICE_BUCKET_CHALLENGE: helper.can_get_obsidian(),
    }
