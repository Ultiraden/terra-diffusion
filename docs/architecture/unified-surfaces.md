# Owning provider surface rules

Issue12 separates provider surface materials from catalog eligibility and biome
selection. The owning TD source and density pipeline remain authoritative.

Existing legacy, compatibility-routing and accepted 140-biome catalog-only
palettes retain TerralithSurfaceRules unchanged. UnifiedSurfaceRules activates
only when a catalog-only saved pool explicitly contains Nature's Spirit or Spawn.
These expanded pools require fresh worlds; this bridge does not expand a pool.

Provider rules are imported through the pinned public makeRules ABI and guarded
by the owning source's possible biome keys in that provider namespace. A missing
required adapter fails explicitly. Vanilla/Terralith's imported Overworld tree is
separately guarded to Minecraft/Terralith keys. The existing TD surface rule is
the final material fallback; no biome-selector fallback entry is introduced.
The fallback is read from the owning registered TD noise-settings resource,
because direct saved settings can already contain an earlier merged bridge.
Surface imports must not accumulate on world reopen.

Only the surface rule in NoiseGeneratorSettings is replaced. Noise settings,
density router, default block/fluid, spawn target, sea level and generation flags
are carried through. Repeated application is tracked per owning generator.
This does not repair the separate #70 provider/cache lifecycle findings.

The dedicated fresh TD instance must set sugi_and_stratified_pillars=false in
the misc section of config/natures_spirit-common.toml. The pinned optional
hook adds column geometry in sugi/stratified/dune biomes, so it is disabled to
preserve TD terrain. Authored NS material rules remain enabled. The pack owns
the explicit configuration fragment; existing profiles are not auto-configured.

Expanded 201 worlds also require the explicit TD/Spawn injector compatibility
datapack from issue 114, introduced with TD_m1.9. It keeps the owning source
direct and avoids post-catalog biome substitution. The generic owning source is
initialized before upstream Spawn level-load queries. This bridge does not
change Spawn discovery, structures, biome repair or M2 island lifecycles.

Provider identity ownership remains separate: M1 NS selects the approved 49
inland identities; its two reserved coasts remain inventoried. M1 Spawn selects
ant_gardens and supplies the shared ten-ID contracts; M2 #18 owns actual
ocean/coast placement and the six structure-owned island/tide-pool identities.
Importing a surface rule is not evidence that physical coastal placement works.

The approved fresh M1 pool is 201 possible IDs: accepted 140 + all 51 NS + all 10
Spawn. Namespace dispatch therefore has 51 NS keys, 10 Spawn keys and 137
Minecraft/Terralith keys, followed by the registered TD material rule. The
remaining three TD identities use that material rule. This source change does
not install a preset or expand an existing world's saved pool; provider issues
own the pack's explicitly enabled fresh-world delivery.

Acceptance uses actual normal NOISE/SURFACE/FULL generation, independent raw-NBT
block-state/occupancy/material/biome reconstruction, full resolved authored-rule
comparisons, high terrain at both scales, public-release Terralith comparisons,
old-pool/optional-absence controls and natural reopen checks. Private forced
catalog targets isolate material dispatch without replacing density or placing
features manually; they are not production climate/coast/island acceptance.
The pack's issue 12 evidence records fixture inputs and exact scope.

Build/release gates include strict common checks and both NeoForge variants,
clean source-archive byte reproduction, complete source/pack diff review,
public artifact verification and pack CI. Material fallback is separate from
biome eligibility: no biome-selector fallback entry is added.
