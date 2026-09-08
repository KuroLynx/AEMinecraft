# -----------------------------------------------------------------------
# Region names (must match the MCRegion enum values in regions.py)
# -----------------------------------------------------------------------
REGION_OVERWORLD = "Overworld"
REGION_NETHER = "Nether"
REGION_END = "The End"

# -----------------------------------------------------------------------
# Knowledge items
# -----------------------------------------------------------------------

K_ARMOR = "Armor Handling"
K_AXE = "Axe Handling"
K_BREWING = "Brewing"
K_BRUSH = "Brush Handling"
K_ENCHANT = "Enchanting"
K_FISHING = "Fishing"
K_FLYING = "Flying"
K_HOE = "Hoe Handling"
K_MACE = "Mace Handling"
K_PICKAXE = "Pickaxe Handling"
K_PYRO = "Pyromaniac"
K_BOW = "Sharpshooter"
K_SHEAR = "Shear Handling"
K_SHIELD = "Shield Handling"
K_SHOVEL = "Shovel Handling"
K_SPEAR = "Spear Handling"
K_SWORD = "Sword Handling"
K_TRIDENT = "Trident Handling"

# -----------------------------------------------------------------------
# Structures
# -----------------------------------------------------------------------

# Structures are identified by their game_id (the stable key in structures.json / the MC registry),
# NOT a display name — names are derived via prettify(game_id) for the YAML option and AP item label.
# So a new structure needs no constant unless logic references it (boss gates, structure-bound mobs).
S_ANCIENT_CITY = "ancient_city"
S_BASTION_REMNANT = "bastion_remnant"
S_BURIED_TREASURE = "buried_treasure"
S_DESERT_PYRAMID = "desert_pyramid"
S_DESERT_WELL = "desert_well"
S_DUNGEON = "monster_room"
S_END_CITY = "end_city"
S_IGLOO = "igloo"
S_JUNGLE_PYRAMID = "jungle_pyramid"
S_MANSION = "mansion"
S_MINESHAFT = "mineshaft"
S_MINESHAFT_MESA = "mineshaft_mesa"
S_NETHER_FORTRESS = "fortress"
S_OCEAN_MONUMENT = "monument"
S_OCEAN_RUIN_COLD = "ocean_ruin_cold"
S_OCEAN_RUIN_WARM = "ocean_ruin_warm"
S_PILLAGER_OUTPOST = "pillager_outpost"
S_RUINED_PORTAL = "ruined_portal"
S_RUINED_PORTAL_DESERT = "ruined_portal_desert"
S_RUINED_PORTAL_JUNGLE = "ruined_portal_jungle"
S_RUINED_PORTAL_MOUNTAIN = "ruined_portal_mountain"
S_RUINED_PORTAL_NETHER = "ruined_portal_nether"
S_RUINED_PORTAL_OCEAN = "ruined_portal_ocean"
S_RUINED_PORTAL_SWAMP = "ruined_portal_swamp"
S_SHIPWRECK = "shipwreck"
S_SHIPWRECK_BEACHED = "shipwreck_beached"
S_STRONGHOLD = "stronghold"
S_SWAMP_HUT = "swamp_hut"
S_TRAIL_RUINS = "trail_ruins"
S_TRIAL_CHAMBERS = "trial_chambers"
S_VILLAGE_DESERT = "village_desert"
S_VILLAGE_PLAINS = "village_plains"
S_VILLAGE_SAVANNA = "village_savanna"
S_VILLAGE_SNOWY = "village_snowy"
S_VILLAGE_TAIGA = "village_taiga"

# -----------------------------------------------------------------------
# Advancements
# -----------------------------------------------------------------------

A_ACQUIRE_HARDWARE = "Acquire Hardware"
A_ADVENTURE = "Adventure"
A_ADVENTURING_TIME = "Adventuring Time"
A_ARBALISTIC = "Arbalistic"
A_A_BALANCED_DIET = "A Balanced Diet"
A_A_COMPLETE_CATALOGUE = "A Complete Catalogue"
A_A_FURIOUS_COCKTAIL = "A Furious Cocktail"
A_A_TERRIBLE_FORTRESS = "A Terrible Fortress"
A_A_THROWAWAY_JOKE = "A Throwaway Joke"
A_BEACONATOR = "Beaconator"
A_BEE_OUR_GUEST = "Bee Our Guest"
A_BEST_FRIENDS_FOREVER = "Best Friends Forever"
A_BIRTHDAY_SONG = "Birthday Song"
A_BLOWBACK = "Blowback"
A_BRING_HOME_THE_BEACON = "Bring Home the Beacon"
A_BUKKIT_BUKKIT = "Bukkit Bukkit"
A_BULLSEYE = "Bullseye"
A_CAREFUL_RESTORATION = "Careful Restoration"
A_CAVES_AND_CLIFFS = "Caves & Cliffs"
A_COUNTRY_LODE_TAKE_ME_HOME = "Country Lode Take Me Home"
A_COVER_ME_IN_DEBRIS = "Cover Me in Debris"
A_COVER_ME_WITH_DIAMONDS = "Cover Me with Diamonds"
A_CRAFTERS_CRAFTING_CRAFTERS = "Crafters Crafting Crafters"
A_CRAFTING_A_NEW_LOOK = "Crafting a New Look"
A_DIAMONDS = "Diamonds!"
A_ENCHANTER = "Enchanter"
A_END_ROOT = "The End"
A_ENTER_END_PORTAL = "The End?"
A_EYE_SPY = "Eye Spy"
A_FEELS_LIKE_HOME = "Feels Like Home"
A_FISHY_BUSINESS = "Fishy Business"
A_FREE_THE_END = "Free the End"
A_GETTING_AN_UPGRADE = "Getting an Upgrade"
A_GLOW_AND_BEHOLD = "Glow and Behold!"
A_GOOD_AS_NEW = "Good as New"
A_GREAT_VIEW_FROM_UP_HERE = "Great View From Up Here"
A_THE_HEALING_POWER_OF_FRIENDSHIP = "The Healing Power of Friendship!"
A_HEART_TRANSPLANTER = "Heart Transplanter"
A_HERO_OF_THE_VILLAGE = "Hero of the Village"
A_HIDDEN_IN_THE_DEPTHS = "Hidden in the Depths"
A_HIRED_HELP = "Hired Help"
A_HOT_STUFF = "Hot Stuff"
A_HOT_TOURIST_DESTINATIONS = "Hot Tourist Destinations"
A_HOW_DID_WE_GET_HERE = "How Did We Get Here?"
A_HUSBANDRY = "Husbandry"
A_ICE_BUCKET_CHALLENGE = "Ice Bucket Challenge"
A_INTO_FIRE = "Into Fire"
A_ISNT_IT_IRON_PICK = "Isn't It Iron Pick"
A_ISNT_IT_SCUTE = "Isn't It Scute?"
A_IS_IT_A_BALLOON = "Is It a Balloon?"
A_IS_IT_A_BIRD = "Is It a Bird?"
A_IS_IT_A_PLANE = "Is It a Plane?"
A_IT_SPREADS = "It Spreads"
A_LIGHTEN_UP = "Lighten Up"
A_LIGHT_AS_A_RABBIT = "Light as a Rabbit"
A_LITTLE_SNIFFS = "Little Sniffs"
A_LOCAL_BREWERY = "Local Brewery"
A_MINECRAFT = "Minecraft"
A_MOB_KABOB = "Mob Kabob"
A_MONSTERS_HUNTED = "Monsters Hunted"
A_MONSTER_HUNTER = "Monster Hunter"
A_NETHER_ROOT = "Nether"
A_NOT_QUITE_NINE_LIVES = "Not Quite Nine Lives"
A_NOT_TODAY = "Not Today Thank You"
A_OH_SHINY = "Oh Shiny"
A_OL_BETSY = "Ol' Betsy"
A_OVER_OVERKILL = "Over-Overkill"
A_PLANTING_THE_PAST = "Planting the Past"
A_POSTMORTAL = "Postmortal"
A_REMOTE_GETAWAY = "Remote Getaway"
A_RESPECTING_THE_REMNANTS = "Respecting the Remnants"
A_RETURN_TO_SENDER = "Return to Sender"
A_REVAULTING = "Revaulting"
A_A_SEEDY_PLACE = "A Seedy Place"
A_SERIOUS_DEDICATION = "Serious Dedication"
A_SHEAR_BRILLIANCE = "Shear Brilliance"
A_SKY_IS_THE_LIMIT = "Sky's the Limit"
A_SMELLS_INTERESTING = "Smells Interesting"
A_SMITHING_WITH_STYLE = "Smithing with Style"
A_SNEAK_100 = "Sneak 100"
A_SNIPER_DUEL = "Sniper Duel"
A_SOUND_OF_MUSIC = "Sound of Music"
A_SPOOKY_SCARY_SKELETON = "Spooky Scary Skeleton"
A_STAR_TRADER = "Star Trader"
A_STAY_HYDRATED = "Stay Hydrated!"
A_STICKY_SITUATION = "Sticky Situation"
A_STONE_AGE = "Stone Age"
A_SUBSPACE_BUBBLE = "Subspace Bubble"
A_SUIT_UP = "Suit Up"
A_SURGE_PROTECTOR = "Surge Protector"
A_SWEET_DREAMS = "Sweet Dreams"
A_TACTICAL_FISHING = "Tactical Fishing"
A_TAKE_AIM = "Take Aim"
A_THE_CITY_AT_THE_END_OF_THE_GAME = "The City at the End of the Game"
A_THE_CUTEST_PREDATOR = "The Cutest Predator"
A_THE_END_AGAIN = "The End... Again..."
A_THE_NEXT_GENERATION = "The Next Generation"
A_THE_PARROTS_AND_THE_BATS = "The Parrots and the Bats"
A_THE_POWER_OF_BOOKS = "The Power of Books"
A_THE_WHOLE_PACK = "The Whole Pack"
A_THIS_BOAT_HAS_LEGS = "This Boat Has Legs"
A_THOSE_WERE_THE_DAYS = "Those Were the Days"
A_TOTAL_BEELOCATION = "Total Beelocation"
A_TRIAL_EDITION = "Minecraft: Trial(s) Edition"
A_TWO_BIRDS_ONE_ARROW = "Two Birds One Arrow"
A_TWO_BY_TWO = "Two by Two"
A_UNDER_LOCK_AND_KEY = "Under Lock and Key"
A_UNEASY_ALLIANCE = "Uneasy Alliance"
A_VERY_VERY_FRIGHTENING = "Very Very Frightening"
A_VOLUNTARY_EXILE = "Voluntary Exile"
A_WAR_PIGS = "War Pigs"
A_WAX_OFF = "Wax Off"
A_WAX_ON = "Wax On"
A_WE_NEED_TO_GO_DEEPER = "We Need to Go Deeper"
A_WHATEVER_FLOATS_YOUR_GOAT = "Whatever Floats Your Goat!"
A_WHAT_A_DEAL = "What a Deal!"
A_WHEN_THE_SQUAD_HOPS_INTO_TOWN = "When the Squad Hops into Town"
A_WHO_IS_THE_PILLAGER_NOW = "Who's the Pillager Now?"
A_WHO_IS_CUTTING_ONIONS = "Who is Cutting Onions?"
A_WHO_NEEDS_ROCKETS = "Who Needs Rockets?"
A_WITHERING_HEIGHTS = "Withering Heights"
A_WITH_OUR_POWERS_COMBINED = "With Our Powers Combined!"
A_YOU_NEED_A_MINT = "You Need a Mint"
A_YOU_VE_GOT_A_FRIEND_IN_ME = "You've Got a Friend in Me"
A_ZOMBIE_DOCTOR = "Zombie Doctor"

# -----------------------------------------------------------------------
# Items
# -----------------------------------------------------------------------

ITEM_DIMENSION_OVERWORLD = "Dimension Unlock: Overworld"
ITEM_DIMENSION_NETHER = "Dimension Unlock: Nether"
ITEM_DIMENSION_END = "Dimension Unlock: The End"
ITEM_VILLAGER_TRUST = "Progressive Villager Trust"
ITEM_MATERIAL_HANDLING = "Progressive Material Handling"
ITEM_STRUCTURE_FINDER = "Progressive Structure Finder"
ITEM_BIOME_FINDER = "Biome Finder"
ITEM_KEEP_INVENTORY = "Progressive Keep Inventory"
ITEM_INVENTORY_SLOT = "Progressive Inventory Slot"

# -----------------------------------------------------------------------
# Prefix
# -----------------------------------------------------------------------

ADVANCEMENT_PREFIX = "Advancement: "
# The K_* names above are the BARE knowledge ("Sword Handling"); this makes the AP item name. Also how
# content/knowledges.csv rows (bare names + a category) become items.
KNOWLEDGE_PREFIX = "Knowledge: "
ENTITY_UNLOCK_PREFIX = "Entity Unlock: "
STRUCT_UNLOCK_PREFIX = "Structure Unlock: "
ENTITY_KILL_PREFIX = "Kill Entity: "
BOSS_KILL_PREFIX = "Kill Boss: "
# Internal (non-networked) event location/item per item granted by a BACAP advancement reward. The
# event location's rule is the OR of reaching a granting advancement; acquire() then sources the item
# via has(<this>) — a non-recursive leaf, so AP's event sweep resolves rewards monotonically instead
# of the recursive reached() that forms acquire(X) -> reached(A) -> A's rule -> acquire(X) cycles.
REWARD_EVENT_PREFIX = "Reward: "

# -----------------------------------------------------------------------
# Mobs — Passive
# -----------------------------------------------------------------------

E_ALLAY = "Allay"
E_ARMADILLO = "Armadillo"
E_AXOLOTL = "Axolotl"
E_BAT = "Bat"
E_CAMEL = "Camel"
E_CAMEL_HUSK = "Camel Husk"
E_CAT = "Cat"
E_CHICKEN = "Chicken"
E_COD = "Cod"
E_COPPER_GOLEM = "Copper Golem"
E_COW = "Cow"
E_DONKEY = "Donkey"
E_FROG = "Frog"
E_GLOW_SQUID = "Glow Squid"
E_HAPPY_GHAST = "Happy Ghast"
E_HORSE = "Horse"
E_MOOSHROOM = "Mooshroom"
E_MULE = "Mule"
E_OCELOT = "Ocelot"
E_PARROT = "Parrot"
E_PIG = "Pig"
E_RABBIT = "Rabbit"
E_SALMON = "Salmon"
E_SHEEP = "Sheep"
E_SKELETON_HORSE = "Skeleton Horse"
E_SNIFFER = "Sniffer"
E_SNOW_GOLEM = "Snow Golem"
E_SQUID = "Squid"
E_STRIDER = "Strider"
E_TADPOLE = "Tadpole"
E_TROPICAL_FISH = "Tropical Fish"
E_TURTLE = "Turtle"
E_VILLAGER = "Villager"
E_WANDERING_TRADER = "Wandering Trader"
E_ZOMBIE_HORSE = "Zombie Horse"

# -----------------------------------------------------------------------
# Mobs — Neutral
# -----------------------------------------------------------------------

E_BEE = "Bee"
E_CAVE_SPIDER = "Cave Spider"
E_DOLPHIN = "Dolphin"
E_DROWNED = "Drowned"
E_ENDERMAN = "Enderman"
E_FOX = "Fox"
E_GOAT = "Goat"
E_IRON_GOLEM = "Iron Golem"
E_LLAMA = "Llama"
E_NAUTILUS = "Nautilus"
E_PANDA = "Panda"
E_PIGLIN = "Piglin"
E_POLAR_BEAR = "Polar Bear"
E_PUFFERFISH = "Pufferfish"
E_SPIDER = "Spider"
E_TRADER_LLAMA = "Trader Llama"
E_WOLF = "Wolf"
E_ZOMBIE_NAUTILUS = "Zombie Nautilus"
E_ZOMBIFIED_PIGLIN = "Zombified Piglin"

# -----------------------------------------------------------------------
# Mobs — Hostile
# -----------------------------------------------------------------------

E_BLAZE = "Blaze"
E_BOGGED = "Bogged"
E_BREEZE = "Breeze"
E_CREAKING = "Creaking"
E_CREEPER = "Creeper"
E_ENDERMITE = "Endermite"
E_EVOKER = "Evoker"
E_GHAST = "Ghast"
E_GUARDIAN = "Guardian"
E_HOGLIN = "Hoglin"
E_HUSK = "Husk"
E_MAGMA_CUBE = "Magma Cube"
E_PARCHED = "Parched"
E_PHANTOM = "Phantom"
E_PIGLIN_BRUTE = "Piglin Brute"
E_PILLAGER = "Pillager"
E_RAVAGER = "Ravager"
E_SHULKER = "Shulker"
E_SILVERFISH = "Silverfish"
E_SKELETON = "Skeleton"
E_SLIME = "Slime"
E_STRAY = "Stray"
E_VEX = "Vex"
E_VINDICATOR = "Vindicator"
E_WITCH = "Witch"
E_WITHER_SKELETON = "Wither Skeleton"
E_ZOGLIN = "Zoglin"
E_ZOMBIE = "Zombie"
E_ZOMBIE_VILLAGER = "Zombie Villager"

# The mobs a raid wave spawns — mirrors Minecraft's own `Raid.RaiderType` enum, which is what the
# wave table actually draws from. NOT the `#minecraft:raiders` entity tag: that also carries the
# Illusioner, which is a member of the tag but never spawns in a raid.
#
# A raid needs every one of them: waves spawn each type, and a wave only clears once its raiders are
# dead, so a single locked member stalls the raid forever. The mod refuses to convert Bad Omen into
# Raid Omen until all of them are unlocked (see MobSpawnLockService#isAnyRaidMobLocked), and
# can_win_raid mirrors that gate.
MOBS_RAID = (E_PILLAGER, E_VINDICATOR, E_EVOKER, E_WITCH, E_RAVAGER)

# -----------------------------------------------------------------------
# Mobs — Bosses
# -----------------------------------------------------------------------

E_ELDER_GUARDIAN = "Elder Guardian"
E_ENDER_DRAGON = "Ender Dragon"
E_WARDEN = "Warden"
E_WITHER = "Wither"

# -----------------------------------------------------------------------
# Materials
# -----------------------------------------------------------------------
MAT_WOOD = 0
MAT_STONE = 1
MAT_COPPER = 2
MAT_IRON = 3
MAT_GOLD = 4
MAT_DIAMOND = 5
MAT_NETHERITE = 6

# -----------------------------------------------------------------------
# Content packs (packs/<name>/)
# -----------------------------------------------------------------------
# The ONLY pack-related constant: the MC version the apworld targets. Packs are not referenced by
# folder name anywhere — content.registry DISCOVERS them by scanning packs/ and keeping every pack
# whose meta.json `mc_version` equals this string (the vanilla one is the base; the rest, e.g. BACAP,
# are overlays). So supporting a new MC version is: dump the packs in-game (their meta.mc_version is
# the running game version), drop the folders in packs/, and bump this one line — folder names are
# irrelevant. Must match exactly the `mc_version` the dump writes (SharedConstants version name).
CONTENT_VERSION = "26.1.2"
