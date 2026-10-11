# Classifier developer observations (issue #7 candidate)

The candidate `1.3.0-m1.6` adds `/packdebug biome` on NeoForge. It is pending
native command validation and acceptance. The accepted pack remains pinned to
`TD_m1.5` until delivery is reviewed. No profile or world enables tracing automatically.

Start a developer JVM with the complete argument quoted by the launching shell:

```text
-Dterradiff.debug.biome.positions=512,-512;-4,-4
```

The empty default disables capture. Configuration is a strict list of at most
64 unique integer block X/Z pairs, separated by `;`, with coordinates divisible
by four. Malformed, duplicate, nonaligned and overflowing coordinates reject
startup. Positions are fixed for that process; commands never add watches.
Normal generation must encounter a configured position before its trace exists.
Reopening a saved chunk does not reconstruct its precise classifier inputs.

Permission level 2 is required:

```text
/packdebug biome
/packdebug biome page 2
/packdebug biome verbose 1
/packdebug biome 512 100 -512
/packdebug biome 512 100 -512 verbose 2
```

The first form uses the player position. Console callers supply block coordinates.
Queries report requested block coordinates, the persisted quart coordinates, and
the classifier column at the quart origin. Normal pages contain 16 hard-eligible
entries with targets, weights, priorities and fallback metadata; `page <n>`
selects another eligible page. Verbose pages contain 16 resolved entries,
including rejected entries and all their reasons. Both use ASCII resource-ID order. Unsupported dimensions/generators
and Y outside the world's build height fail explicitly.

## Two different observations

The **persisted quart biome** comes from a completed, already visible full chunk's
section palette. The lookup uses NeoForge's existing public access to
`ChunkMap.getVisibleChunkIfPresent`, then `getFullChunkFuture().getNow`, then
`LevelChunkSection.getNoiseBiome`. It does not wait, request a ticket, call a
biome source or load a missing chunk. `getChunkNow` is deliberately avoided:
the pinned implementation increments the profiler and can update its lookup cache.
The stored quart biome is not the fuzzy block-biome lookup used by `BiomeManager.getBiome`.

The **captured surface choice** comes from a successfully completed ordinary
canonical provider tile. It describes the actual classification column, before
cave overrides and persisted chunk history. It can differ from the current
palette. A missing loaded chunk or trace produces an explicit unavailable result.
Commands never call `peekHeightmap`, fetch, pipeline/inference APIs, cache clocks,
generation, hydrology or diagnostic-reporting methods.

## Exact inputs and selection

Capture copies all 34 `TerrainSample` fields after the unchanged noise, ecological
derivation and slope overrides. The catalog context uses the same
`SampleCatalogContext.from` adapter. Native tiles classify uncarved model elevation
with the actual padded Sobel slope. Upsampled tiles classify the smooth resized
elevation and upsampled climate at `30 / scale` metres per pixel, before terrain
detail noise/carved/converted heights. The actual wet mask is retained.

Numeric context values preserve the resulting f32 value and promote it exactly
to f64 for display and catalog comparisons. Verbose output includes the f32 bits.
Temperature and summer maximum are Celsius; precipitation is model millimetres;
classification elevation is model metres, not Minecraft Y; slope/aridity/moisture
are dimensionless; growing season is days. Temperature seasonality is the existing
model value divided by 100, and precipitation CV is the unconverted model value.
Physical bathymetry, water depth, coast and submerged remain unavailable.

Each resolved catalog entry retains hard eligibility and all deterministic
rejection reasons, plus weight, priority and fallback metadata. Zero weight can
be hard-eligible while selection-disabled. The transitional bridge's actual gate,
chosen exact mapping and no-match reason are observed at their production calls;
no noise branch is replayed. Its ordered policy ignores generic priority/fallback
tiers. Inactive catalogs use `legacy`; a world without active Terralith uses the
existing fallback palette. Those paths have no selected catalog entry. A fixed
frozen river also has no selected entry. Generic coherent selection remains #6.

## Provenance, lifecycle and bounded retention

The enqueue-captured immutable catalog supplies the trace, including its enabled
state and a SHA-256 of definitions in captured snapshot order (winning resource
order for loader snapshots). Later reloads never recompute old records. Successful
regeneration can replace a position's observation. Each record identifies seed,
scale and the exact canonical tile key; noncanonical overlapping requests do not
publish watches. Reads neither refresh retention nor inspect terrain cache state.
Retention is cleared at owning server start/stop and provider seed changes. An
epoch prevents a previously queued tile from publishing into a later lifecycle.

There are at most 64 records, a 1 MiB estimated structured-record cap, and a
16 MiB estimated retained-record cap. One in-progress tile also has a 16 MiB
accounting cap. These are conservative accounting quotas, not measurements or
absolute bounds on JVM heap/transient allocation. Catalog definitions and registry
holders are not retained in completed records. UTF-8 digest encoding uses a fixed
4 KiB scratch buffer; resource string estimates use overflow-safe long arithmetic.
Oversized records or total quota excess produce explicit budget-unavailable
status; no partial eligibility list is presented as complete. Configuring 64
positions does not guarantee that all 64 full catalog traces fit the quota.

## Validation status

The issue #7 source adapter retains every accepted #5 oracle assertion. It adds
production debug-on/off comparisons for 16 classifier grids at native and
upsampled pixel sizes, with outputs, snow bytes, all 34 derived fields and
diagnostic counters unchanged. It tests full 429-entry records, sorted reasons,
selection metadata, unavailable/nonfinite fields, zero-weight exact-mapping
failure, missing climate, lifecycle epochs, old captured catalogs and 64-position
quota status. Actual owning provider publication, command permission, loaded
palette reads, reload retention and no-mutation fingerprints require the separate
reviewed native runtime gate before acceptance.
