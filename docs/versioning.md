# Versioning & Releases

This project ships **two artifacts that are released together** under one version, so a player grabs
a single matched set:

- the **mod** — the Fabric mod jar (`aem-<version>+<mc>.jar`)
- the **apworld** — the Archipelago world (`minecraft_aem-<version>.apworld`)

## One version: `VERSION`

[`VERSION`](../VERSION) at the repo root is the **single source of truth** — one `X.Y.Z` you bump.
CI stamps it into both artifacts and publishes them together as one GitHub Release `vX.Y.Z`:

- the mod is built with `mod_version = <VERSION>` → `aem-<VERSION>+<mc>.jar`
- the apworld's `world_version` is set to `<VERSION>` → `minecraft_aem-<VERSION>.apworld`

The `mod_version` in `gradle.properties` and the `world_version` in `archipelago.json` are only
local-dev defaults; CI overrides both from `VERSION`.

### Two things that are deliberately NOT the release version

| Value | Where | What it is |
|-------|-------|------------|
| `minecraft_version` | [`gradle.properties`](../archipelago-euclesia-minecraft/gradle.properties) | The Minecraft version the mod targets — a **build input**, shown only in the jar name (`aem-1.0.0+26.1.2.jar`). Retargeting MC is just a `minecraft_version` bump; it does not change `VERSION`. |
| `SLOT_DATA_VERSION` | [`minecraft_aem/__init__.py`](../minecraft_aem/__init__.py) | The mod↔apworld **compatibility** schema version (see below). A small integer, bumped rarely and independently of `VERSION`. |

## Cutting a release

1. Open a PR that bumps [`VERSION`](../VERSION) (e.g. `1.0.0` → `1.1.0`).
2. Merge the PR into `master`.
3. CI builds both artifacts and publishes **one** GitHub Release `v1.1.0` with both files attached
   and the tag created on the merge commit.

That's it — **bumping `VERSION` in a merged PR is the release signal**. The workflow triggers only on
a **PR merge into `master`** (`pull_request: closed` + `merged`), so **direct pushes to `master` never
release**. It publishes only when the merge actually changed `VERSION`, so ordinary PRs that don't
touch it never publish; as a safety net it also skips if that `v<VERSION>` is already released, so a
re-run can't double-publish. To also move to a new Minecraft version in the same release, bump
`minecraft_version` in `gradle.properties` alongside `VERSION`.

### What lands on GitHub Releases

```
v1.1.0            Latest
  🔖 tag: v1.1.0  •  target: <merge commit on master>
  Assets:
    • aem-1.1.0+26.1.2.jar         ← mod
    • minecraft_aem-1.1.0.apworld  ← apworld
    • Source code (zip / tar.gz)
```

### Dry runs

Run the **Release** workflow manually from the **Actions** tab (`workflow_dispatch`) to build and
package both artifacts and upload them as workflow artifacts **without** publishing a release —
useful for checking a build before bumping `VERSION`.

## Mod ↔ apworld compatibility

Separate from the release version, the mod and apworld share a **slot-data schema version**, so a
mismatched pair is caught at connect time instead of failing mysteriously in-game:

- The apworld advertises `SLOT_DATA_VERSION` (in `minecraft_aem/__init__.py`) as `slot_data_version`
  in slot data.
- The mod declares the range it understands (`CompatibilityService.MIN_SUPPORTED..MAX_SUPPORTED`).
- On connect, if the world's version is **newer** than the mod supports, the mod refuses to enter the
  world (via the pre-flight `ArchipelagoConnectingScreen`) with *"update the mod"*; if it's **older**
  than the mod's minimum, *"regenerate with a newer apworld"*. Slot data with no version field
  (pre-versioning seeds) is treated as legacy and allowed.

Bump it only on a *breaking* slot-data change (it changes far less often than `VERSION`):

- **`SLOT_DATA_VERSION`** (apworld) — increment when `fill_slot_data()` changes in a way an older mod
  can't read (a renamed/removed field, or a new field the mod must have). Purely additive fields the
  mod tolerates when absent do **not** need a bump.
- **`CompatibilityService.MAX_SUPPORTED`** (mod) — increment when the mod is updated to read that new
  schema. Raise `MIN_SUPPORTED` only when dropping support for an old schema.

A breaking change touches both sides in the same release: bump `SLOT_DATA_VERSION`, teach the mod to
read it, and bump `MAX_SUPPORTED`.

## Notes

- **apworld naming.** The world package is `minecraft_aem/` (not `minecraft/`) so the shipped
  `minecraft_aem.apworld` does not collide with Archipelago's built-in Minecraft world when a user
  installs it. If you develop against a local Archipelago checkout, install the world there as
  `worlds/minecraft_aem/` to match (the `tools/` scripts import `worlds.minecraft_aem`).
- **`archipelago.json`.** Its `game` (`"Minecraft [AEM]"`) must match the world's `game` in
  `minecraft_aem/__init__.py`; `minimum_ap_version` is the oldest Archipelago version the world
  supports; and `world_version` **must be numeric `major.minor.build`** (Archipelago parses it as a
  version tuple), which is why `VERSION` is plain `X.Y.Z`.
