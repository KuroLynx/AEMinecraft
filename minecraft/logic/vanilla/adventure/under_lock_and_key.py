from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def under_lock_and_key(helper: RuleHelper) -> dict:
    return {
        # Unlock a Vault with a Trial Key. The Trial Key is found in the chamber's *entrance* chest
        # (loot table chests/trial_chambers/entrance) — no combat required — so simply reaching the
        # Trial Chambers (and thus its entrance chest and a Vault) is sufficient.
        A_UNDER_LOCK_AND_KEY: helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}")
    }
