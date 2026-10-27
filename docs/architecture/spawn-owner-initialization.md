# Generic TD ownership before provider consumers

M1 issue #114 fixes the demonstrated Spawn level-load query ordering without
changing selection policy, terrain derivation or Spawn's island lifecycle.

Direct generic TD worlds initialize their owning catalog/provider session at
NeoForge HIGHEST level-load priority. The pinned Spawn consumer uses NORMAL.
The normal TD handler skips these generic worlds, preventing duplicate binding.
Legacy and non-TD worlds keep their accepted NORMAL initialization path.

The pack's explicit fresh-world TD compatibility datapack separately prevents
seven redundant/identity-replacing Spawn Lithostitched injectors from wrapping
the source. No generic wrapper-unwrapping abstraction is introduced here.
Required possible-biome IDs still come from the owning fixed saved pool.

Two isolated native controls establish the problem: a wrapped generic owner
fails through InjectorBiomeSource; disabling the injectors on accepted TD_m1.8
retains the direct source but still fails before owner initialization. The
corrected source passes the pinned Spawn consumer, real owning classification,
201-ID palette, 771-feature ordering and normal FULL chunk generation in the
isolated native fixture. The no-TD control keeps upstream injectors active.
These are support/ownership gates, not M2 placement acceptance.

No #70 cache/hydrology fix, classifier entry/weight/fallback change or island
discovery/repair/structure patch is included. M2 #18 retains lifecycle and
physical-placement acceptance.
