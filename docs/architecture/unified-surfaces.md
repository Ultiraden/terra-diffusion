# Issue 12 candidate: owning provider surfaces

This branch is a candidate. No native surface/material acceptance or release is
claimed by this design note.

Existing legacy, compatibility-routing and accepted 140-biome catalog-only
palettes retain TerralithSurfaceRules unchanged. UnifiedSurfaceRules activates
only when a catalog-only saved pool explicitly contains Nature's Spirit or Spawn.
These expanded pools require fresh worlds; this bridge does not expand a pool.

Provider rules are imported through the pinned public makeRules ABI and guarded
by the owning source's possible biome keys in that provider namespace. A missing
required adapter fails explicitly. Vanilla/Terralith's imported Overworld tree is
separately guarded to Minecraft/Terralith keys. The existing TD surface rule is
the final material fallback; no biome-selector fallback entry is introduced.

Only the surface rule in NoiseGeneratorSettings is replaced. Noise settings,
density router, default block/fluid, spawn target, sea level and generation flags
are carried through. Repeated application is tracked per owning generator.
This does not repair the separate #70 provider/cache lifecycle findings.

The dedicated fresh TD instance must disable Nature's Spirit's optional
sugi_and_stratified_pillars common-config hook, which adds column geometry.
NS material surfaces remain enabled. Existing profiles are not auto-configured.

Provider identity ownership remains separate: M1 NS selects the approved 49
inland identities; its two reserved coasts remain inventoried. M1 Spawn selects
ant_gardens and supplies the shared ten-ID contracts; M2 #18 owns actual
ocean/coast placement and the six structure-owned island/tide-pool identities.
Importing a surface rule is not evidence that physical coastal placement works.

Before merge: resolved-rule namespace/idempotence and unchanged density tests;
normal special-material surfaces at both scales, high-altitude TD terrain,
Terralith preservation and generic-material fallback; old policy/pool controls;
optional-provider guards; strict builds, clean artifact reproduction, public
release verification, complete diff review and pack CI.
