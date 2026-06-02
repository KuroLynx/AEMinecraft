from dataclasses import dataclass

from Options import Choice, OptionSet, PerGameCommonOptions, Range, Toggle

from .data import MOBS_BOSS, STRUCTURES


class BossList(OptionSet):
    """The bosses you must defeat to complete your game.

    You must defeat every boss listed here. Use the special value "All" to require every boss.

    Valid values: "All", or any boss name (Ender Dragon, Elder Guardian, Warden, Wither).

    Examples:
        Require every boss:
            - All

        Require only the Ender Dragon:
            - Ender Dragon
    """
    display_name = "Boss List"
    valid_keys = {"All"} | set(MOBS_BOSS.keys())
    default = frozenset({"All"})


class AdvancementsRequired(Range):
    """Number of advancements you must complete as an additional victory condition.

    Set to 0 to disable this condition entirely.
    This is cumulative with the boss kill condition: all active conditions must be met to win.

    Minimum value is 0
    Maximum value is 125 (the total number of advancements)
    """
    display_name = "Advancements Required"
    range_start = 0
    range_end = 125
    default = 0


class DeathLink(Toggle):
    """When you die, all players with Death Link enabled die too.

    Of course the reverse is true too: when any of them dies, you die.
    """
    display_name = "Death Link"
    option_true = 1
    option_false = 0
    default = 0


class VillagerTrust(Toggle):
    """If enabled, trading with villagers requires receiving Progressive Villager Trust items.

    One item per trade level, 5 levels total.
    These items are added to the multiworld item pool.
    """
    display_name = "Villager Trust"
    option_true = 1
    option_false = 0
    default = 0


class KillSanity(Toggle):
    """If enabled, killing a mob for the first time sends a location check.

    Bosses always send a check regardless of this setting.
    """
    display_name = "Kill Sanity"
    option_true = 1
    option_false = 0
    default = 0


class MobSpawnLockCategory(OptionSet):
    """Define which mob categories are locked until their unlock item is received.

    When a mob category is locked, mobs of that type will not spawn in the world
    until the corresponding 'Entity Unlock: <mob>' item is received from the multiworld.

    Leave empty to disable mob spawn locking entirely.

    Locking "boss" gates the bosses (Ender Dragon, Elder Guardian, Warden, Wither) behind their
    'Entity Unlock: <boss>' item: they cannot spawn — and so their kill check and the goal that
    depends on them stays out of logic — until that item is received. Note that an Ocean Monument
    that generates while Elder Guardian is locked stays without its elder guardians (monument mobs
    are not retro-spawned); pair a boss lock with sensible logic if that matters to you.

    Valid values: passive, neutral, hostile, boss

    Examples:
        Lock only hostiles:
            - hostile

        Lock everything including bosses:
            - passive
            - neutral
            - hostile
            - boss
    """
    display_name = "Mob Spawn Lock Category"
    valid_keys = {"passive", "neutral", "hostile", "boss"}
    default = frozenset()


class StructureUnlock(OptionSet):
    """Define which structures are locked until their unlock item is received.

    When a structure is locked, it cannot be used (in logic) until the corresponding
    'Structure Unlock: <structure>' item is received from the multiworld. Structures that
    are not locked are always available once their dimension is reachable.

    Leave empty to disable structure locking entirely.

    Accepts dimension presets, the special value "All", and/or individual structure names
    (you can mix them freely):
        - Dimension presets: "Overworld", "Nether", "The End" — lock every structure of that
          dimension.
        - "All" — lock every structure.
        - Any structure name (e.g. "Ancient City", "Village (Plains)", "Nether Fortress").

    Examples:
        Lock everything in the Nether plus the Ancient City:
            - Nether
            - Ancient City

        Lock all structures:
            - All
    """
    display_name = "Structure Unlock"
    valid_keys = {"All", "Overworld", "Nether", "The End"} | set(STRUCTURES.keys())
    default = frozenset()


class TrapChance(Range):
    """The probability for each filler item to be replaced with a trap item.
    Set to 0 to disable traps entirely.

    Minimum value is 0
    Maximum value is 100
    """
    display_name = "Trap Chance"
    range_start = 0
    range_end = 100
    default = 30


class ChallengeSanity(Toggle):
    """If enabled, includes extremely complex advancements in the location pool.

    This includes:
    - A Furious Cocktail (have all potion effects simultaneously)
    - How Did We Get Here? (have all status effects simultaneously)
    - Arbalistic (Kill 5 unique mobs with one arrow)
    - A Balanced Diet (Eat every food items)

    These advancements are very difficult to complete and are excluded by default.
    """
    display_name = "Challenge Sanity"
    option_true = 1
    option_false = 0
    default = 0


class StartDimension(Choice):
    """The dimension you spawn in at the start of the game.

    - overworld (default): the classic start. The Nether and The End each require their
      'Dimension Unlock' item to reach.
    - nether: you spawn in the Nether (no Nether unlock item is added — you start there).
      Reaching the Overworld then requires the 'Dimension Unlock: Overworld' item, a Nether
      ruined portal (the obsidian to build the return portal) and the means to light it
      (Knowledge: Pyromaniac). The End is still reached from the Overworld.

    The End cannot be a start dimension: it has no resources to gear up with, so the seed
    would not be logically completable.
    """
    display_name = "Start Dimension"
    option_overworld = 0
    option_nether = 1
    default = 0


@dataclass
class MCOptions(PerGameCommonOptions):
    boss_list: BossList
    start_dimension: StartDimension
    advancements_required: AdvancementsRequired
    death_link: DeathLink
    villager_trust: VillagerTrust
    kill_sanity: KillSanity
    mob_spawn_lock_category: MobSpawnLockCategory
    structure_unlock: StructureUnlock
    trap_chance: TrapChance
    challenge_sanity: ChallengeSanity
