from .. import *  # constants, RuleHelper, mob sets (re-export hub)


def smithing_with_style(helper: RuleHelper) -> dict:
    return {
        A_SMITHING_WITH_STYLE: helper.all_of(
            helper.knowledge(K_ARMOR),
            helper.material(MAT_IRON),
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THE_CITY_AT_THE_END_OF_THE_GAME}"),  # Spire — End City
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_THOSE_WERE_THE_DAYS}"),  # Snout/Netherite Upgrade — Bastion
            helper.reached(f"{ADVANCEMENT_PREFIX}{A_A_TERRIBLE_FORTRESS}"),  # Rib — Nether Fortress
            helper.structure(S_ANCIENT_CITY),  # Ward + Silence
            helper.structure(S_MANSION),  # Vex
            helper.entity(E_ELDER_GUARDIAN),  # Tide — Kill on Elder Guardian
            helper.all_of(helper.has_brush(), helper.structure(S_TRAIL_RUINS)),  # Wayfinder
        )
    }
