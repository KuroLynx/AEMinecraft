# Setup Guide — AEMinecraft

*Minecraft Randomizer by KuroLynx (mod by EDGN)*

---

## Required Software

| Component | Version | Download |
| --- | --- | --- |
| **Minecraft: Java Edition** | `26.1.2` | — |
| **Fabric Loader** | `0.19.2` or newer | [fabricmc.net](https://fabricmc.net/use/installer/) |
| **Fabric API** | `0.149.0+26.1.2` | [github.com/FabricMC/fabric-api](https://github.com/FabricMC/fabric-api/releases) |
| **AEMinecraft mod** (`.jar`) | latest release | [Latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest) |
| **AEMinecraft apworld** (`.apworld`) | latest release | [Latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest) |
| **Archipelago** | `0.6.7` or newer | [archipelago.gg](https://archipelago.gg/) |

> Download the `.jar` and `.apworld` from the **same** release so their versions match.

## Optional Software

| Component | Compatible Version | Download |
| --- | --- | --- |
| **BlazeAndCave's Advancement Pack (BACAP)** | `1.20.3` | [Download (MediaFire)](https://www.mediafire.com/file/z5x3r2gzscrd4ui/BlazeandCave%2527s_Advancements_Pack_1.20.3.zip/file) |

---

## Installation

1. Install **Fabric Loader** for Minecraft `26.1.2`.
2. Drop the following into your `.minecraft/mods/` folder:
   - the **AEMinecraft** mod `.jar`
   - **Fabric API**
3. Place the **AEMinecraft** `.apworld` in your Archipelago installation
   (double-clicking the `.apworld` with the Archipelago Launcher installed will install it for you).

---

## Creating a Config File (`.yaml`)

You need a player YAML (your "config") before you can generate or join a game. Pick either method.

### Option A — From a template (recommended)

1. Download the **`template.yaml`** attached to the [latest release](https://github.com/KuroLynx/ArchipelagoMinecraft/releases/latest).
2. Edit it to your liking — every option is documented with inline comments.

### Option B — With the Archipelago Options tool

*(Requires the Archipelago Launcher, with the AEM `.apworld` installed.)*

Use the **options / template generator** in your Archipelago Launcher to produce a valid YAML for
**AEMinecraft**, then customize it.

---

## Joining a MultiWorld Game

### First time (creating your world)

1. Launch Minecraft with the required mods installed (**AEMinecraft** + **Fabric API**).
2. Click **Singleplayer**, then **Create New World**.
3. Open the **Archipelago** tab (far right of the tab bar at the top of the create-world screen).
4. Enter the **Address**, **Port**, **Slot name**, and **Password** (if the room needs one) into the
   correct fields.
5. **Install any datapacks your config requires** (e.g. BACAP). Still on the create-world screen:
   1. Open the **More** tab.
   2. Click **Data Packs**.
   3. If your datapack isn't listed, click **Open Pack Folder**.
      1. Copy (or drag & drop) your datapack into the folder that opens.
      2. Return to the game — your datapack now appears under **Available**.
   4. Hover the datapack and click the arrow to move it to **Selected**.
   5. Click **Done**.
6. *(Optional)* Adjust any game rules you want under the **More** tab.
7. Click **Create New World**.

> **Note:** the **first** time you open a world it must reach the Archipelago room, because that is
> when the room hands over everything the world needs to know (which advancements are checks, what is
> locked, and so on). If it cannot connect, you are returned to the title screen. Every load after
> that can fall back to the saved copy — see [Playing offline](#playing-offline).

### After the first time (rejoining)

1. Launch Minecraft with the required mods installed.
2. Click **Singleplayer**.
3. Open your existing world — it reconnects to its Archipelago slot automatically.

> Each Archipelago world is tied to the slot it was created with. The most recently created world is
> the one at the top of your Singleplayer list.

## Playing With Friends (Multiplayer)

An Archipelago slot belongs to a **world**, not to a person. Everyone playing in the same Minecraft
world is playing the same slot together: one item pool, one set of checks, one goal. (Two people who
each want their own slot need two worlds and two YAMLs, the same as two players of any other game.)

### What everyone needs

Every player who connects needs the same **Minecraft version**, **Fabric API**, and the **same
AEMinecraft `.jar`** as the host — the mod adds its own items and effects, so a client without it
cannot join.

Nobody else needs the rest: datapacks such as **BACAP** live in the world, so only the host or the
server installs them, and the `.apworld` is only needed by whoever generates the multiworld.

### Option A — Open to LAN

1. Create the world exactly as above — the **Archipelago** tab is what ties it to your slot.
2. In-game, press **Esc** → **Open to LAN**. Turn **Allow Cheats** on if you want to be able to run
   `/aem` commands.
3. Your friends join through **Multiplayer** on the same network.

Your game holds the Archipelago session, so when you close the world the run stops for everyone.

### Option B — Dedicated server

1. Install a **Fabric server** for Minecraft `26.1.2`.
2. Put the **AEMinecraft** `.jar` and **Fabric API** into the server's `mods/` folder.
3. Put any datapacks your config requires (e.g. BACAP) into `world/datapacks/`.
4. Start the server once. The mod writes a starter **`config/aem.json`** and logs where it is.
5. Fill that file in, then restart:

```json
{
  "slot": "YourSlotName",
  "address": "archipelago.gg",
  "port": "38281",
  "password": "",
  "connectOnStart": true
}
```

| Field | Meaning |
| --- | --- |
| `slot` | Your slot name in the room. Left blank, the server boots with no session at all. |
| `address` / `port` | The Archipelago room to join. |
| `password` | The room's password, or `""` when it has none. |
| `connectOnStart` | Set it to `false` to boot idle and connect by hand with `/aem connect` — the usual choice while a room is still being set up. |

A dedicated server has no create-world screen, and this file is what replaces it. It only ever
**seeds** a world that has no slot yet: after the first use the slot is written into
`<world>/archipelago/`, and that copy wins from then on — so a save moved to another host carries its
own slot along instead of quietly adopting the new host's. To point a world at a different room, use
`/aem connect`.

> **Until the server has its slot data, players are held at the door** with *"This server has not
> connected to Archipelago yet."* That is deliberate: a server that cannot tell locked content from
> unlocked would generate locked structures for real, permanently. Connect from the console with
> `/aem connect "wss://archipelago.gg:38281" "YourSlotName"` (a third argument is the room password;
> the `wss://` scheme is required here), and everyone can come in. A world that has already cached its
> slot data holds nobody out — it simply plays [offline](#playing-offline).

### What the run shares, and what stays yours

| Shared by the whole run | Yours alone |
| --- | --- |
| **Advancements**, and the individual criteria inside them — one player's thirty biomes and another's ten add up | **BACAP rewards** — everyone completes the shared advancement for real, so everyone gets their own reward chest and trophy |
| **Checks** — a location is sent once, by whoever reaches it first | **Filler buffs** — each player receives every one, including the ones that landed while they were logged off |
| **Unlocks** — mobs, structures, dimensions, materials, Knowledge | **Finders** — every player is handed their own Biome Finder, and the structure locator bar draws on each player's own HUD |
| The **goal**, and whether the run is online or offline | **Traps** — they fire on whoever is in the world at the time, and are never banked for latecomers |

A player who joins tomorrow, or the first one back after a restart, is caught up on everything the run
has done — half-finished advancements included — instead of starting from their own empty book.

> **DeathLink on a server:** your deaths go to the room once each, however many of you are online, and
> a death arriving from another world takes **one** random living player rather than wiping the
> server.

### Server commands

| Command | Who can run it | What it does |
| --- | --- | --- |
| `/aem status` | anyone | Online or offline, the slot, and how many checks are queued |
| `/aem say <message>` | anyone | Sends a message to Archipelago chat (needs a live link) |
| `/aem connect "<uri>" "<slot>" ["<password>"]` | anyone | Opens a session on a server that started idle |
| `/aem reconnect` | operators | Retries this world's saved slot and flushes everything earned offline |
| `/aem deathlink [on\|off\|default]` | operators | DeathLink for the whole run, saved in the world. On a dedicated server this is the only way to change it — the client screen's toggle has no session to talk to |

## Playing offline

Once a world has connected successfully **even once**, it keeps a copy of its slot data and can be
played without the room. If Archipelago is unreachable when you load the world — or the link drops
while you are playing — the run simply continues on that saved copy instead of ending the session.

**What still works:** everything the world decides for itself. Locks stay exactly as strict as they
were (mobs, structures, dimensions, materials, Knowledge), advancements complete normally, and the
tracker and in-logic indicator keep working.

**What waits for the link:** the two things that need the room.

| | Offline | On reconnect |
|---|---|---|
| **Checks you earn** | Saved in the world | Sent automatically |
| **Items other worlds send you** | Cannot arrive | Delivered |
| **Archipelago chat** (`/aem say`) | Unavailable | Works again |

So an offline run can make progress but cannot *receive* it — you will keep earning checks, and
nothing new will unlock until you are back online. Finishing your goal offline is recorded too, and
reported the next time you connect.

### Getting back online

Run **`/aem reconnect`** (operator/gamemaster level). It retries the world's own saved slot, and on
success everything you earned offline is sent to the room. Nothing is lost if it fails — the queue is
saved in the world, so it survives restarts and crashes and goes out whenever the link next returns.

Run **`/aem status`** to see whether you are online or offline and how many checks are waiting.

> **The one exception:** a world that has *never* completed a connection has nothing saved to fall
> back on. It cannot tell locked content from unlocked, and generating terrain in that state would
> place locked structures for real, permanently. Those worlds still refuse to load without a room.
