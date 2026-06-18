from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def ice_bucket_challenge(helper: RuleHelper) -> dict:
    return {
        # "Obtain obsidian" — reuse the shared, region-aware obsidian rule so this advancement and
        # the Nether→Overworld portal gate stay in sync.
        A_ICE_BUCKET_CHALLENGE: helper.can_get_obsidian(),
    }
