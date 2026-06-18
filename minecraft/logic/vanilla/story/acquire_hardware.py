from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def acquire_hardware(helper: RuleHelper) -> dict:
    return {
        A_ACQUIRE_HARDWARE: helper.all_of(
            helper.material(MAT_IRON),  # You always need to have unlocked iron handling
            helper.any_of(
                # Mine iron ore with a stone pickaxe — iron ore is Overworld-only (the Nether yields
                # iron only from chests/barter, handled by the branches below), so gate the mining
                # proxy on the Overworld now that the advancement is no longer region-locked.
                helper.all_of(
                    helper.reached(f"{ADVANCEMENT_PREFIX}{A_STONE_AGE}"),
                    helper.access_region(REGION_OVERWORLD),
                ),
                helper.any_village(),  # Find it in any village chests
                helper.any_mineshaft(),  # Find it in any mineshaft chests
                helper.structure(S_BURIED_TREASURE),
                helper.any_shipwreck(),
                helper.structure(S_DESERT_PYRAMID),
                helper.structure(S_JUNGLE_PYRAMID),
                helper.structure(S_MANSION),
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_TRIAL_EDITION}"),

                helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # In Stronghold
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # In Nether Fortress
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # In Bastion Remnant
                helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # In End City

                helper.can_barter(),  # Piglin trade gives nuggets

                helper.any_portal(True),
                helper.has_any_entities(E_HUSK, E_IRON_GOLEM, E_ZOMBIE, E_ZOMBIE_VILLAGER),
            ),
        ),
    }
