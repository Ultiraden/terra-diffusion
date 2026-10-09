# Surface catalog loading and filtering (M1 issues #4/#5)

Normative v1 contract: [ulti-world catalog specification](https://github.com/Ultiraden/ulti-world/blob/f9a475cfd554db3eb5d8ba755837a34fcc6fd5c0/docs/architecture/biome-catalog.md).
This fork loads validated immutable candidates and exposes generic hard filtering
with an explicit transitional Terralith classification binding. Existing profile
worlds remain on the legacy path unless enabled datapacks explicitly supply valid
activation metadata. Normal NeoForge world-load binds the registry-only supplier.
Environmental derivation, terrain, hydrology, surface and cave policy retain their
current inputs and behavior.

`BiomeCatalogLoader.propose` parses and validates every winning resource before
optional-mod and registry skips. It is side-effect free. `activate` publishes an
accepted immutable snapshot atomically; `reload` combines those operations for
standalone users. Initial state is inactive. Failed proposals retain the published
snapshot. Empty valid catalogs are active and contain no candidates; callers still
need their existing no-match classification policy. Definitions retain zero weights
as valid metadata; this loader does not perform selection.

Snapshot entries use ASCII resource-ID order. `byId` and `byEnvironment` return
immutable definitions with resolved registry biome holders. Range comparisons use
binary64 bounds and promote existing float samples. `Range.matches(OptionalDouble)`
and predicate matching fail closed for absent/nonfinite constrained inputs; they do
not create bathymetry, water depth, coast or submerged adapters. #5 supplies the
existing derived context and candidate filtering. #6 owns coherent selection.

NeoForge `AddReloadListenerEvent` occurs after built-in/worldgen registries freeze.
The listener uses winning `ResourceManager.listResources` entries, stages validation
and resolves biome holders against that reload's frozen registry. Publication occurs
only on `TagsUpdatedEvent.SERVER_DATA_LOAD`, after the aggregate resource reload
successfully completes. The initial event precedes world creation/chunk generation;
on `/reload`, Minecraft first installs the successful resources and then binds tags.
Client tag packets never publish catalog state.

The singleton `terrain-diffusion-mc:terrain_diffusion/catalog_activation.json`
requires exactly `schema_version:1` and boolean `enabled`. Missing/false keeps
classification inactive even with nonempty loaded data; true with valid empty data
is active and uses vanilla no-match fallback. Whole-resource pack precedence applies
to the marker separately from entries. `CatalogClassificationState` validates both
inputs and publishes one immutable pair only after aggregate success. Initial
malformed marker/catalog stays inactive; failed reload retains both prior values.
Explicit disabling/removing a marker affects later uncached tiles, preserving
cached/pending snapshots and keys. No resources or activation defaults are built in.
NeoForge resets only the catalog binding at `ServerAboutToStartEvent`, then binds
it during Overworld load. Its later `ServerStartingEvent` keeps the original
provider-cache clearing without erasing classification activation.

Published state is keyed by the biome registry object, which is stable over reloads
and differs between world registries. Pending proposals are keyed by the exact frozen
`RegistryAccess` wrapper for one reload cycle. `LayeredRegistryAccess.compositeAccess`
returns its cached object; `ReloadableServerResources.fullRegistryHolder` strongly
retains that same object through the successful tags event. The concrete mapped
registry/access implementations use identity equality (verified in runtime logs).
Thus a failed overall reload cannot publish its valid catalog on another cycle's
tags event. Weak pending keys expire with discarded failed resources; publication
consumes only its own pending proposal and leaves concurrent cycles independent.
`ServerStoppedEvent` releases that server's published and pending catalog state.
Fabric lifecycle integration is not enabled by this NeoForge pack change.

Run `gradlew :common:catalogTest :neoforge:build -PuseCuda=true`. The dependency-free
catalog test task compiles the production parser/model/loader sources and covers
schema, lexical JSON, cross-field, immutable/query, precedence, mod/registry skips,
overflow, unavailable inputs, staging and failed reload behavior. Runtime lifecycle
tests live in the pack's `scripts/test-catalog-runtime.cjs`; its test-only probe mod
injects an unrelated listener failure and queries published state. It is never
included in ordinary profiles or this distributable JAR.

`catalogFilterTest`, `catalogSnapshotSourceTest` and `catalogClassificationStateTest`
are also part of `:common:check`.
The pack's `scripts/m1-catalog-filter/verify.py` compiles the active bridge and pinned
original derivation for differential checks, including nonfinite inputs that first
appear after a tile's first column. That source suite does not compile Minecraft
lifecycle/provider dependencies or replace strict artifact and runtime acceptance.
The pack's `scripts/m1-catalog-filter/test-runtime.cjs` uses real datapack winners,
normal NeoForge world-load binding and a read-only observer for activation/reload
checks. It never sets classification state through a private setter.

Default NeoForge builds use pinned Gradle 8.14.3, ModDevGradle 2.0.123, SHA-256 dependency
verification and deterministic archive order/timestamps. `scripts/bootstrap-dml.ps1`
extracts the exact SHA-256 DirectML dependency from the checksum-pinned original TD
Windows release. The pack's source lock pins the accepted commit, Temurin runtime
patch and final Windows/CUDA artifact SHA-512 values. Run its build script with the
recorded JDK; arbitrary JDK 21 patches are not claimed byte-identical. Verification
fails if remote dependency inputs drift instead of silently accepting them.
Fabric and pinned Loom 1.13.6 require explicit `-PincludeFabric=true`; see
[build-input verification](build-input-verification.md) for the existing fresh
generated-mappings limitation and verified-cache scope.
