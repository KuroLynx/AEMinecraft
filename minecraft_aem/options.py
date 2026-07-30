from dataclasses import dataclass

from Options import Choice, OptionDict, OptionError, OptionSet, PerGameCommonOptions, Range, Toggle

from .data import (
    ADVANCEMENT_LOCATIONS,
    KNOWLEDGES,
    KNOWLEDGES_BY_CATEGORY,
    MCKnowledgeCategory,
    MOBS_ALL,
    MOBS_BOSS,
    STRUCTURES,
)


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
    default = frozenset({"Ender Dragon"})


class AdvancementsRequired(Range):
    """Number of advancements you must complete to win.

    This is an extra victory condition on top of the boss kills: you must satisfy every active
    condition to finish. Set to 0 to require no advancements.

    If you ask for more advancements than your other options actually make available, the
    requirement is lowered to fit, so you can safely set a high value.

    Minimum value is 0.
    """
    display_name = "Advancements Required"
    range_start = 0
    # Derived from the loaded content so the cap always matches the bundled manifests (vanilla +
    # BACAP); it shifts automatically when a manifest is updated for a new/older game version. The
    # generator still clamps the chosen value down to the advancements that exist in each seed.
    range_end = len(ADVANCEMENT_LOCATIONS)
    default = 75


class DeathLink(Toggle):
    """When you die, all players with Death Link enabled die too.

    Of course the reverse is true too: when any of them dies, you die.
    """
    display_name = "Death Link"
    option_true = 1
    option_false = 0
    default = 1


class VillagerTrust(Toggle):
    """Gate villager trading behind items you find in the multiworld.

    When enabled, villagers will only trade once you have received enough Progressive Villager Trust
    items. There are five trade levels, and each item unlocks the next one.
    """
    display_name = "Villager Trust"
    option_true = 1
    option_false = 0
    default = 1


class KillSanity(Toggle):
    """If enabled, killing a mob for the first time sends a location check.

    Bosses always send a check regardless of this setting.
    """
    display_name = "Kill Sanity"
    option_true = 1
    option_false = 0
    default = 0


class MobSpawnLock(OptionSet):
    """Lock chosen mobs from spawning until you unlock them.

    A locked mob will not spawn anywhere until you receive its matching 'Entity Unlock: <mob>' item
    from the multiworld. Mobs you do not list spawn normally. Leave the list empty to let everything
    spawn.

    Accepts category presets, the special value "All", and/or individual mob names (you can mix them
    freely):
        - Category presets: "passive", "neutral", "hostile", "boss" — lock every mob of that kind.
        - "All" — lock every mob.
        - Any mob name (e.g. "Creeper", "Zombie", "Warden").

    Locking a boss holds it back (Ender Dragon, Elder Guardian, Warden, Wither) until its unlock item
    arrives, so it cannot be killed before then. Mobs that would have appeared in a structure while
    locked (for example an Ocean Monument's elder guardians) are not lost: they are placed once you
    receive their unlock item.

    Examples:
        Lock all hostiles plus the Ender Dragon:
            - hostile
            - Ender Dragon

        Lock only creepers:
            - Creeper
    """
    display_name = "Mob Spawn Lock"
    valid_keys = {"All", "passive", "neutral", "hostile", "boss"} | set(MOBS_ALL.keys())
    default = frozenset({"boss"})


class StructureUnlock(OptionSet):
    """Lock chosen structures until you unlock them.

    A locked structure stays sealed until you receive its matching 'Structure Unlock: <structure>'
    item from the multiworld. Structures you do not list are available as soon as you can reach their
    dimension. Leave the list empty to keep every structure available from the start.

    Accepts dimension presets, the special value "All", and/or individual structure names
    (you can mix them freely):
        - Dimension presets: "Overworld", "Nether", "The End" — lock every structure of that
          dimension.
        - "All" — lock every structure.
        - Any structure name (e.g. "Ancient City", "Village Plains", "Fortress").

    Examples:
        Lock everything in the Nether plus the Ancient City:
            - Nether
            - Ancient City

        Lock all structures:
            - All
    """
    display_name = "Structure Unlock"
    valid_keys = {"All", "Overworld", "Nether", "The End"} | {data.label for data in STRUCTURES.values()}
    default = frozenset({"Stronghold"})


class KnowledgeGates(OptionSet):
    """Choose which Knowledge gates your run uses.

    A Knowledge gate holds something back until its 'Knowledge: <name>' item arrives from the
    multiworld. Gates you do not list are OFF: that knowledge is not in the item pool at all and the
    thing it would have gated is free from the start. Leave the list empty for a run with no Knowledge
    gates whatsoever.

    Each gate blocks BOTH ways of getting at what it covers:
        - tool / armor / misc gate an ITEM — you can neither craft nor pick up the tool, armor piece or
          gear until the Knowledge arrives (a diamond sword also still needs its material tier).
        - station / container gate a BLOCK — you can neither craft/pick it up NOR use it, so a locked
          furnace can't be made and a furnace found in a village won't open either.

    Accepts category presets, the special value "All", and/or individual knowledge names (mix freely):
        - Category presets: "tool", "armor", "misc", "station", "container".
        - "All" — every gate this content version knows about.
        - Any knowledge name, WITHOUT the "Knowledge: " prefix (e.g. "Pickaxe Handling", "Brewing").

    The default is the classic set: every tool, armor and misc gate, plus the brewing stand and
    enchanting table. The other station and container gates are opt-in, since gating something like the
    crafting table or the chest reshapes the whole run.

    Examples:
        The classic set plus every workstation:
            - tool
            - armor
            - misc
            - station

        Only mining and smelting matter:
            - Pickaxe Handling
            - Furnace
    """
    display_name = "Knowledge Gates"
    valid_keys = {"All"} | {category for category in KNOWLEDGES_BY_CATEGORY} | set(KNOWLEDGES.keys())
    # The gates that existed before this option: every tool/armor/misc knowledge, plus the two stations
    # that were already gated (brewing stand, enchanting table). Named individually rather than via the
    # "station" preset so that dumping a new pack — which appends station/container rows — never
    # silently turns new gates on for a player who never asked for them.
    default = frozenset({MCKnowledgeCategory.TOOL, MCKnowledgeCategory.ARMOR, MCKnowledgeCategory.MISC,
                         "Brewing", "Enchanting"})


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
    """Add the hardest advancements as checks.

    When enabled, every advancement with the challenge frame (the spiky border) becomes a location
    check, for example:
    - A Furious Cocktail (have all potion effects at once)
    - How Did We Get Here? (have all status effects at once)
    - Arbalistic (kill 5 unique mobs with one arrow)
    - A Balanced Diet (eat every food item)

    If you also enabled blazeandcave, its challenge advancements are included too. These are very
    demanding to complete, so they are left out by default.
    """
    display_name = "Challenge Sanity"
    option_true = 1
    option_false = 0
    default = 0


class StartDimension(Choice):
    """The dimension you spawn in at the start of the game.

    - overworld (default): the classic start. Reaching the Nether and The End each needs their
      'Dimension Unlock' item.
    - nether: you start trapped in the Nether. Getting out to the Overworld needs the
      'Dimension Unlock: Overworld' item, plus a way to build and light a return portal. The End is
      still reached from the Overworld afterwards.

    You cannot start in The End, as there is no way to gear up there.
    """
    display_name = "Start Dimension"
    option_overworld = 0
    option_nether = 1
    default = 0


class StructureFinder(Choice):
    """A locator bar that points you toward nearby structures.

    The Structure Finder shows nearby structures on an on-screen bar. It is progressive: each copy you
    collect reveals more of the structures around you, until eventually every one in range is shown.

    - in_pool (default): the copies are shuffled into the multiworld for you to find.
    - start: you begin with the full finder already revealed.
    - disabled: the Structure Finder is not used in this game.

    Note: each time you load into the world, it takes about 5-10 extra seconds to find the structures
    around you before you can start playing.
    """
    display_name = "Structure Finder"
    option_disabled = 0
    option_start = 1
    option_in_pool = 2
    default = 2


class BiomeFinder(Choice):
    """How the Biome Finder is handled.

    The Biome Finder is a compass that stays with you when you die. Right-click it, pick a biome in
    your current dimension, and its needle points to the nearest one.

    - in_pool (default): the compass is shuffled into the multiworld for you to find.
    - start: you begin with the compass.
    - disabled: the Biome Finder is not used in this game.
    """
    display_name = "Biome Finder"
    option_disabled = 0
    option_start = 1
    option_in_pool = 2
    default = 2


class BlazeAndCave(Toggle):
    """Include the BlazeandCave's Advancements Pack as extra checks.

    When enabled, the many custom advancements from BlazeandCave's Advancements Pack (BACAP) become
    location checks alongside the usual ones, greatly expanding your game. Its challenge advancements
    are only included if you also enable challenge_sanity.

    You must have the BlazeandCave's Advancements Pack datapack installed in your world for these
    checks to work. Leave this disabled if you are not playing with that datapack.
    """
    display_name = "BlazeandCave's Advancements Pack"
    option_true = 1
    option_false = 0
    default = 0


class BacapRewards(Toggle):
    """Let BlazeandCave's Advancements Pack hand out its own item and XP rewards.

    BACAP normally grants items and experience when you complete its advancements. In a randomizer
    those free rewards bypass the multiworld economy, so they are turned OFF by default: the mod runs
    BACAP's reward-disable functions the first time you load the world.

    - disabled (default): BACAP gives no item or XP rewards.
    - enabled: BACAP keeps its vanilla item and XP rewards.

    Either way, BACAP trophies are always disabled. Only has an effect when blazeandcave is enabled.
    """
    display_name = "BlazeandCave Rewards"
    option_true = 1
    option_false = 0
    default = 0


class ItemGateBehavior(OptionDict):
    """Decide, per acquisition route, whether a still-locked item is blocked.

    A "locked" item is one the material/tool gates aren't satisfied for yet: a raw material you don't
    have enough Progressive Material Handling for, or a tool/armor piece missing its Knowledge item or
    material tier. This option chooses, for each route by which such an item could reach your
    inventory, whether that route is gated (blocked) or left open.

    Five routes are configurable, each set to true (gated) or false (allowed):
        - crafting: taking a locked item out of a crafting grid — the crafting table or your own 2x2
          inventory grid.
        - station: taking a locked item out of a workstation that makes or transforms items — furnace,
          blast furnace, smoker, anvil, smithing table, grindstone, stonecutter, loom, cartography
          table, brewing stand, enchanting table, villager trade, crafter.
        - container: taking a locked item out of plain storage — chest, barrel, shulker box, hopper,
          dispenser, ender chest, minecart and mount inventories.
        - pickup: picking a locked item up off the ground.
        - given: the /give command handing you a locked item.

    Meaning of each value:
        - true: the route is gated until the item unlocks (you get a red "requires ..." message).
        - false: the route always lets the item through, even while it is still locked.

    Any route you omit keeps its default (everything gated except given), so you only need to list the
    routes you want to change. As a convenience for configs written before the GUI routes were split,
    an explicit 'crafting' value also becomes the default for 'station' and 'container'.

    Example (block hand-crafting, but let chests, pickups and /give through):
        item_gate_behavior:
            crafting: true
            station: true
            container: false
            pickup: false
            given: false
    """
    display_name = "Item Gate Behavior"
    valid_keys = {"crafting", "station", "container", "pickup", "given"}
    # Preserves the historical behavior: every GUI take and floor pickup is gated, while /give (a newer
    # route) is left open. Omitted keys fall back to these in fill_slot_data().
    default = {"crafting": True, "station": True, "container": True, "pickup": True, "given": False}

    # Accepted spellings of each truth value, so a YAML "yes"/"no"/1/0 works as well as true/false.
    _TRUE = {True, 1, "true", "yes", "gated", "1"}
    _FALSE = {False, 0, "false", "no", "allowed", "0"}

    @classmethod
    def as_bool(cls, value) -> bool:
        key = value.lower() if isinstance(value, str) else value
        if key in cls._TRUE:
            return True
        if key in cls._FALSE:
            return False
        raise OptionError(
            f"item_gate_behavior values must be true (gated) or false (allowed), got '{value}'."
        )

    def verify(self, world, player_name: str, plando_options) -> None:
        super().verify(world, player_name, plando_options)
        for route, behavior in self.value.items():
            try:
                self.as_bool(behavior)
            except OptionError as error:
                raise OptionError(f"Player {player_name}: route '{route}' — {error}")


@dataclass
class MCOptions(PerGameCommonOptions):
    boss_list: BossList
    start_dimension: StartDimension
    advancements_required: AdvancementsRequired
    death_link: DeathLink
    villager_trust: VillagerTrust
    kill_sanity: KillSanity
    knowledge_gates: KnowledgeGates
    mob_spawn_lock: MobSpawnLock
    structure_unlock: StructureUnlock
    trap_chance: TrapChance
    challenge_sanity: ChallengeSanity
    structure_finder: StructureFinder
    biome_finder: BiomeFinder
    blazeandcave: BlazeAndCave
    bacap_rewards: BacapRewards
    item_gate_behavior: ItemGateBehavior
