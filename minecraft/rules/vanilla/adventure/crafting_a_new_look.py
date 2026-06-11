from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def crafting_a_new_look(helper: RuleHelper) -> dict:
    return {
        A_CRAFTING_A_NEW_LOOK: helper.all_of(
        helper.knowledge(K_ARMOR),
        helper.material(MAT_IRON),
        # Trim locations
        helper.any_of(
            helper.entity(E_ELDER_GUARDIAN),  # Tide — Kill on Elder Guardian
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Snout/Netherite Upgrade — Bastion
            helper.structure(S_PILLAGER_OUTPOST),  # Sentry
            helper.structure(S_MANSION),  # Vex
            helper.structure(S_JUNGLE_PYRAMID),  # Wild
            helper.any_shipwreck(),  # Coast
            helper.structure(S_DESERT_PYRAMID),  # Dune
            helper.structure(S_ANCIENT_CITY),  # Ward + Silence
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Rib — Nether Fortress
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_EYE_SPY}"),  # Eye — Stronghold
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # Spire — End City
            helper.all_of(helper.has_brush(), helper.structure(S_TRAIL_RUINS))  # Wayfinder/Raiser/Shaper/Host
        )
    )
    }
