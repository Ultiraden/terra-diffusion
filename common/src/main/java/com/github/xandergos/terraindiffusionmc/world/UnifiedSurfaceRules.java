package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.mixin.NoiseBasedChunkGeneratorAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** Surface-only dispatch for explicitly expanded catalog-only world palettes.
 * Previous saved policies/pools retain the accepted Terralith bridge. */
public final class UnifiedSurfaceRules {
    private static final Logger LOG=LoggerFactory.getLogger(UnifiedSurfaceRules.class);
    private static final Map<NoiseBasedChunkGenerator,NoiseGeneratorSettings> APPLIED=new WeakHashMap<>();
    private UnifiedSurfaceRules(){}

    public static void apply(ServerLevel world) {
        var generator=world.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise)
            || !(generator.getBiomeSource() instanceof TerrainDiffusionBiomeSource source)) {
            TerralithSurfaceRules.apply(world);return;
        }
        var policy=source.selectionPolicy();
        boolean ns=policy.catalogOnly()&&policy.possibleBiomeIds().stream().anyMatch(id->id.startsWith("natures_spirit:"));
        boolean spawn=policy.catalogOnly()&&policy.possibleBiomeIds().stream().anyMatch(id->id.startsWith("spawn:"));
        if (!ns&&!spawn) {TerralithSurfaceRules.apply(world);return;}

        NoiseGeneratorSettings current=noise.generatorSettings().value();
        synchronized(APPLIED) {if(APPLIED.get(noise)==current)return;}
        List<SurfaceRules.RuleSource> parts=new ArrayList<>();
        if(ns)parts.add(guard(source,Set.of("natures_spirit"),
            provider("net.hibiscus.naturespirit.world.NSSurfaceRules")));
        if(spawn)parts.add(guard(source,Set.of("spawn"),
            provider("com.ninni.spawn.registry.SpawnSurfaceRules")));
        if(TerrainDiffusionConfig.terralithInjectSurfaceRules()) {
            var imported=world.registryAccess().registryOrThrow(Registries.NOISE_SETTINGS)
                .getOptional(NoiseGeneratorSettings.OVERWORLD).orElseThrow(
                    ()->new IllegalStateException("Unified surface bridge requires the owning Overworld rule"));
            parts.add(guard(source,Set.of("minecraft","terralith"),imported.surfaceRule()));
        }
        // Provider rules cannot claim unrelated identities. TD's own rule is the
        // final material fallback; its density/router/fluid/height remain untouched.
        parts.add(current.surfaceRule());
        var merged=new NoiseGeneratorSettings(current.noiseSettings(),current.defaultBlock(),
            current.defaultFluid(),current.noiseRouter(),
            SurfaceRules.sequence(parts.toArray(SurfaceRules.RuleSource[]::new)),
            current.spawnTarget(),current.seaLevel(),current.disableMobGeneration(),
            current.isAquifersEnabled(),current.oreVeinsEnabled(),current.useLegacyRandomSource());
        ((NoiseBasedChunkGeneratorAccessor)(Object)noise).terrainDiffusion$setSettings(Holder.direct(merged));
        synchronized(APPLIED){APPLIED.put(noise,merged);}
        LOG.info("Applied owning unified surface dispatch (Nature's Spirit={}, Spawn={}, pool={})",
            ns,spawn,policy.possibleBiomeIds().size());
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static SurfaceRules.RuleSource guard(TerrainDiffusionBiomeSource source,Set<String> namespaces,
                                                 SurfaceRules.RuleSource rule) {
        ResourceKey<Biome>[] keys=source.possibleBiomes().stream().map(h->h.unwrapKey().orElseThrow())
            .filter(key->namespaces.contains(key.location().getNamespace()))
            .sorted(Comparator.comparing(key->key.location().toString()))
            .toArray(ResourceKey[]::new);
        if(keys.length==0)throw new IllegalStateException("No owning biome keys for surface namespaces "+namespaces);
        return SurfaceRules.ifTrue(SurfaceRules.isBiome(keys),rule);
    }

    /** Pinned optional provider ABI. A required adapter failure is explicit; no
     * silent replacement of a selected provider's appearance is accepted. */
    private static SurfaceRules.RuleSource provider(String name) {
        try {
            return (SurfaceRules.RuleSource)Class.forName(name).getMethod("makeRules").invoke(null);
        } catch(ReflectiveOperationException|LinkageError|ClassCastException failure) {
            Throwable cause=failure instanceof InvocationTargetException call?call.getCause():failure;
            throw new IllegalStateException("Unable to load required pinned surface provider "+name,cause);
        }
    }
}
