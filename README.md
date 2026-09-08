# SkyBlock Recipe Viewer (MC 26.2, Java, Fabric)

A small, from-scratch mod — **not** a port of Firmament. It does one thing: downloads the
public [NotEnoughUpdates-REPO](https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO)
item data and registers SkyBlock crafting recipes as REI recipe displays.

## What it deliberately does NOT do
- No self-update-checker (nothing checks GitHub/Modrinth for a newer version of *this* mod).
- No mod-list reporting to the server you connect to (Firmament's `ModAnnouncer`
  equivalent was simply never written).
- The only outbound network calls are to `api.github.com`/`github.com` (public NEU item-data
  repo) and `api.hypixel.net` (Hypixel's own public `/resources/skyblock/items` endpoint,
  used only for skull textures - no API key, no account/player data involved, same public
  data source Hypixel's own website uses).

## How it works
1. `RepoDownloader` — plain `java.net.http.HttpClient` port of Firmament's
   `RepoDownloadManager.kt`: checks the latest commit sha on the repo's `master` branch,
   downloads the archive zip only if it changed, extracts it with the same zip-slip guard.
2. `NeuRepoManager` — loads the extracted folder with `moe.nea:neurepoparser`, the same
   plain-Java library Firmament depends on for parsing NEU repo JSON (so recipe/item parsing
   isn't reinvented — that library already handles the format correctly).
3. `HypixelSkinDownloader` / `HypixelSkinManager` — fetches Hypixel's own public item
   resource and caches the SkyBlock-id -> skull-texture map to
   `config/skyblockrecipeviewer/hypixel-items.json`. `SkyblockItemResolver` uses this as the
   authoritative source for skull ("SKULL_ITEM") items: when a skin exists for an id, the
   display is forced to a `PLAYER_HEAD` carrying that real texture via
   `ResolvableProfile.createResolved(...)`, the same mechanism vanilla uses for any
   NBT-defined custom head. This is verified against the MC 26.1.2 client jar you uploaded
   (`ResolvableProfile`, `DataComponents.PROFILE` both confirmed to exist with this exact
   shape) - **the one part I could not verify against that jar is `com.mojang.authlib`**
   (`GameProfile`/`PropertyMap`/`Property`), since authlib isn't bundled inside
   minecraft-client.jar itself - it's a separate library Loom pulls in automatically. This
   exact 3-arg `Property(name, value, signature)` shape has been stable across effectively
   all of Minecraft's history, but if it doesn't compile, the fix is a one-line constructor
   adjustment, not a redesign.
3. `SkyblockReiPlugin` — a `REIClientPlugin` that registers a "SkyBlock Crafting" category
   and fills it with a 3x3-grid `BasicDisplay` per `NEUCraftingRecipe` found in the repo.
4. `SkyblockItemResolver` — maps a SkyBlock ingredient to the closest vanilla `ItemStack`
   (correct base item + SkyBlock display name). It does **not** reconstruct full NBT/lore —
   that's Firmament's much larger `ItemCache`/DataFixer pipeline, out of scope here.
5. **Reforging / reforge stones** (`repo/reforge/ReforgeData.java`,
   `repo/reforge/ReforgeStore.java`) — reads `constants/reforges.json` (free Blacksmith
   reforges) and `constants/reforgestones.json` (reforge-stone reforges) directly with plain
   Gson, independently of `neurepoparser`'s own typed constants API (see "What I verified vs.
   assumed" below for why). Shows up as a "SkyBlock Reforging" REI category: reforge stone (if
   any) on the left, one text row per rarity with that rarity's stat bonuses and coin cost.
   Looking up a reforge stone item, or any item a reforge explicitly allow-lists by SkyBlock
   id, surfaces the matching reforge(s) via `SkyblockReforgeDisplayGenerator`.
6. **Forge recipes** (`SkyblockForgeCategory`/`Display`/`DisplayGenerator`) — every
   `NEUForgeRecipe` embedded in the repo's items (`NeuRepoManager.getForgeRecipes()`), shown
   inputs-in-an-arc -> output, with forge time and any extra text as a tooltip on the arrow.
7. **Essence upgrades** (`repo/essence/EssenceUpgradeRecipe.java`,
   `repo/essence/EssenceStore.java`) — reads `constants/essencecosts.json` directly with plain
   Gson (again, see below). One display per star level: item + essence + any extra items on
   the left, a "★N-1 → ★N" label, the same item again on the right (this mod doesn't model
   star-count NBT variants, so both slots resolve to the plain base item - see
   `SkyblockEssenceDisplay`'s class doc).
8. **Pet upgrades (Kat)** (`SkyblockPetUpgradeCategory`/`Display`/`DisplayGenerator`) — every
   `NEUKatUpgradeRecipe` in the repo, shown as pet-in -> pet-out with coins/cost items and an
   upgrade-time tooltip on the arrow.
9. **Mob drops** (`SkyblockMobDropCategory`/`Display`/`DisplayGenerator`) — every
   `NEUMobDropRecipe` in the repo, shown as a grid of drop items with the mob's name/level/
   coins/XP breakdown as a tooltip on a static icon (Firmament renders a live 3D entity here
   via its own `gui/entity` system; this mod doesn't have that machinery, so the full text
   breakdown is still there, just not the live model). Looking up an item shows every mob that
   can drop it.

All five of the above follow the same "live `DynamicDisplayGenerator` + eager
`registry.add(...)` on reload" pattern the crafting category already established — see
`SkyblockCraftingDisplayGenerator`'s class doc for why both exist (Hypixel/multiplayer
reload-timing reliability).

## What I verified vs. assumed
I decompiled the two jars you uploaded (`minecraft-client.jar` at world_version 4903 /
protocol 776, and `RoughlyEnoughItems-26_2_820.jar`) and confirmed real method signatures for:
`Identifier.parse`/`.fromNamespaceAndPath`, `BuiltInRegistries.ITEM`, `ItemStack(ItemLike,int)`,
and the REI `REIClientPlugin` / `DisplayCategory` / `CategoryIdentifier` / `BasicDisplay` /
`EntryStack` / `EntryIngredient` / `Slot` APIs — all the calls used above are exact matches to
what's in those jars, not guesses. The reforging/forge/essence/pet-upgrade/mob-drop GUIs add a
few more REI calls (`Widgets.createLabel`/`.leftAligned()`, `Widgets.withTooltip`,
`Slot.markInput()`/`.markOutput()`/`.disableBackground()`) that aren't exercised by the
crafting category above — these are taken from Firmament's actual compiling usage of the same
REI version family rather than a fresh decompile of this mod's own two jars, so double-check
them first if a build error shows up in `rei/Skyblock*Category.java`.

Two things I could **not** verify because they live outside those two jars, and you should
sanity-check on first build:
- `NEURepository` / `NEUCraftingRecipe` getter names (`getInputs()`, `getOutput()`,
  `getSkyblockId()`, `getAmount()`, `getMinecraftItemId()`, `getDisplayName()`) — taken from
  how Firmament's own Kotlin code calls this library, which is a strong signal but not a
  decompile. The same applies to the new `NEUForgeRecipe` (`getInputs()`, `getOutputStack()`,
  `getDuration()`, `getExtraText()`), `NEUMobDropRecipe` (`getRender()`, `getLevel()`,
  `getName()`, `getCoins()`, the per-skill XP getters, `getExperienceOrbs()`, `getExtra()`,
  `getDrops()`, and its nested `Drop` type with `getDropItem()`/`getExtra()`/`getChance()`),
  and `NEUKatUpgradeRecipe` (`getInput()`, `getOutput()`, `getCoins()`, `getSeconds()`,
  `getItems()`) getters — every one of these is a direct port of a real, currently-compiling
  Firmament call site (see the class docs on each new `Skyblock*Display`/`*Category` file for
  exactly which Firmament source file each was ported from), not a guess made up from
  scratch, but still unverified against an actual decompiled `neurepoparser` jar (not present
  in anything you uploaded). Where Firmament's own Kotlin left it ambiguous whether a getter
  returns an array or a `List`/`Collection` (e.g. `NEUForgeRecipe.getInputs()`,
  `NEUKatUpgradeRecipe.getItems()`), this mod follows the one case that *is* pinned down
  (`NEUCraftingRecipe.getInputs()` is confirmed to return `NEUIngredient[]`, per the existing
  comment in `SkyblockReiPlugin.toDisplay`) and assumes the same array shape throughout, and
  sticks to plain for-each loops (never `.stream()`) on every such getter so the code still
  compiles even if that assumption turns out backwards for a particular one.
- `constants/reforges.json` / `constants/reforgestones.json` field names (`reforgeName`,
  `internalName`, `nbtModifier`, `itemTypes`, `allowOn`, `reforgeCosts`, `reforgeAbility`,
  `reforgeStats`) and `constants/essencecosts.json` field names (`costs`, `type`,
  `essenceCosts`, `itemCosts`) — ported from Firmament's `Reforge.kt`/`ReforgeStore.kt` and
  `EssenceRecipeProvider.kt` respectively (Firmament's own author even flags the essence
  naming convention as "how flimsy" in a code comment). Unlike the crafting-recipe getters
  above, `ReforgeStore`/`EssenceStore` parse these two files directly with plain Gson rather
  than going through `neurepoparser`'s typed constants API (`NEURepository.getConstants()`),
  specifically so a field-name mismatch degrades to "no reforge/essence data loaded" (logged,
  not fatal) instead of a hard compile-time dependency on getter names from a library class
  this mod has no way to decompile or confirm. `EssenceStore` additionally tries a couple of
  plausible alternate key spellings (`essence_costs`/`item_costs` alongside
  `essenceCosts`/`itemCosts`) for the same reason. Load `constants/reforges.json` and
  `constants/essencecosts.json` from your own downloaded repo copy and compare against
  `ReforgeStore.parseReforge`/`EssenceStore.readCostsFile` if the reforge or essence
  categories come up empty after a successful repo download — that's the first place to look.
- Exact `fabric-api` / `fabric-loader` version strings for 26.2 in `gradle.properties` —
  placeholders, check https://fabricmc.net/develop/ once you're set up.

## Multi-version support (26.1.2 + 26.2)
Decompiled both `minecraft-client.jar` builds (world_version 4790 for 26.1.2, 4903 for 26.2)
and both REI jars, and diffed every class this mod actually calls: `Identifier`,
`BuiltInRegistries`, `ItemStack`/`Items`, and the full REI plugin/category/entry/slot API.
All of them are byte-identical between the two versions — so this ships as **one jar**
covering both, no version-splitting (Stonecutter, separate source sets, etc.) needed.

Practically: the project compiles against 26.1.2 (the older of the two — safer default,
since a mod built against the older API is more likely to still run on the newer one than
the reverse), and `fabric.mod.json` declares `"minecraft": ">=26.1.2 <=26.2.x"`.

**One thing I couldn't verify:** Fabric API itself is published as a separate per-version
artifact, and I don't know whether the 26.1.2 build of Fabric API declares itself loadable
on 26.2 too, or whether Fabric splits it there and you'd need to select the right Fabric API
version per game version at runtime (this mod's own code doesn't care either way — it's only
a question of what `fabric_api_version` resolves to). Worth a quick check on
https://modrinth.com/mod/fabric-api/versions when you set up the run configs; if Fabric API
does split there, the fix is just picking the matching Fabric API build per launch, this
mod's jar itself doesn't need to change.

## Building
**Windows:** double-click `build.bat` (or run it from a terminal in this folder). It runs
the included Gradle wrapper and copies the finished jar into this same folder when done.
First run downloads Gradle itself plus all dependencies, so it can take a few minutes and
needs network access to `services.gradle.org`, `maven.fabricmc.net`, `maven.shedaniel.me`,
and `repo.nea.moe`. You'll need a JDK installed (Java 25) and on PATH.

**Manual / other platforms:**
```
./gradlew build
```
The jar lands in `build/libs/`.

I couldn't run this build myself — my sandbox's network allowlist doesn't include those
hosts, so this hasn't been compiled end-to-end. If something doesn't line up (most likely
one of the two bullet points above, or in the multi-version section below), the fix is a
one-line getter rename, not a redesign.
