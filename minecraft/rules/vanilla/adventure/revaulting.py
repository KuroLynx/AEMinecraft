from ...constants import *
from ...helpers import RuleHelper


def revaulting(helper: RuleHelper) -> dict:
    return {
        # Unlock an Ominous Vault with an Ominous Trial Key. The Ominous Trial Key only drops from an
        # *ominous* Trial Spawner (loot table spawners/ominous/trial_chamber/key), so the chamber's
        # mobs must be killable. Going ominous needs an Ominous Bottle (Bad Omen) — which is itself
        # in the chamber's own common Vault (chests/trial_chambers/reward_common), reachable as soon
        # as the Trial Chambers are, so reaching them already covers the Bad Omen. (Voluntary Exile /
        # Pillager Captain is one more Bad Omen source, but redundant, so it is no longer required.)
        A_REVAULTING: helper.all_of(
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),
            helper.has_any_entities(
                E_BREEZE,  # always present
                E_ZOMBIE, E_HUSK, E_SLIME, E_SILVERFISH,  # melee pool
                E_SKELETON, E_STRAY, E_BOGGED,  # ranged pool
                E_SPIDER, E_CAVE_SPIDER,  # small melee pool
            ),
        )
    }
