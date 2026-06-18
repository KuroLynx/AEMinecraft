from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def diamonds(helper: RuleHelper) -> dict:
    return {
        A_DIAMONDS: helper.all_of(
            helper.material(MAT_DIAMOND),
            helper.any_of(
                # Mine it yourself — diamond ore is Overworld-only (no ore in the Nether/End), so
                # this branch must be gated on the Overworld now that the advancement itself is no
                # longer region-locked. Nether players reach diamonds via the chest branches below.
                helper.all_of(helper.knowledge(K_PICKAXE), helper.access_region(REGION_OVERWORLD)),
                helper.any_mineshaft(),  # In Mineshaft
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
                helper.structure(S_DESERT_PYRAMID),  # In Desert Pyramid (Chest)
                helper.structure(S_JUNGLE_PYRAMID),
                helper.structure(S_BURIED_TREASURE),
                helper.any_shipwreck(),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # In Nether Fortress
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold

                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),  # In Trial Chambers (Chest & Pots)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_UNDER_LOCK_AND_KEY}"),  # In Trial Chambers (Vault)
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_REVAULTING}"),  # In Trial Chambers (Ominous Vault)

                helper.any_village(),  # In Village

                helper.all_of(  # In Desert Pyramid (Archeology)
                    helper.structure(S_DESERT_PYRAMID),
                    helper.has_brush(),
                ),
            ),
        ),
    }
