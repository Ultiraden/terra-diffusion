package com.github.xandergos.terraindiffusionmc.catalog.minecraft;

import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog;
import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogLoader;
import com.github.xandergos.terraindiffusionmc.catalog.CatalogClassificationState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.util.*;
import java.util.function.Supplier;

/** Server registry-scoped loading and an explicit supplier for owning classification callers. */
public final class BiomeCatalogRuntime {
    private static final Logger LOG = LoggerFactory.getLogger(BiomeCatalogRuntime.class);
    private static final String DIRECTORY = "terrain_diffusion/biome_catalog/";
    private static final ResourceLocation ACTIVATION=ResourceLocation.parse(CatalogClassificationState.ACTIVATION_RESOURCE);
    // Biomes are worldgen registries and remain stable over /reload. Distinct servers
    // never inherit another registry's holders or last-good snapshot.
    private static final Map<Registry<Biome>, CatalogClassificationState<Holder<Biome>>> LOADERS = new WeakHashMap<>();
    private record Pending(Registry<Biome> registry, CatalogClassificationState<Holder<Biome>> loader,
                           CatalogClassificationState.Proposal<Holder<Biome>> proposal) {}
    public record Prepared(List<BiomeCatalogLoader.Resource> resources, Optional<BiomeCatalogLoader.Resource> activation) {
        public Prepared { resources=List.copyOf(resources); }
    }
    // AddReloadListenerEvent and successful TagsUpdatedEvent share the SAME
    // frozen RegistryAccess instance, while /reload creates a fresh wrapper.
    // A proposal from a failed aggregate reload therefore cannot publish on a
    // later retry's tags event, even when its biome registry object is reused.
    private static final Map<RegistryAccess, Pending> PENDING = new WeakHashMap<>();
    private BiomeCatalogRuntime() {}
    private static synchronized CatalogClassificationState<Holder<Biome>> loader(Registry<Biome> registry) {
        return LOADERS.computeIfAbsent(registry, ignored -> new CatalogClassificationState<>());
    }
    public static BiomeCatalog.Snapshot<Holder<Biome>> snapshot(RegistryAccess access) {
        return loader(access.registryOrThrow(Registries.BIOME)).snapshot();
    }
    /** Caller explicitly owns classification activation. Capture the stable
     * registry, never a ServerLevel or a reload-cycle ResourceManager. */
    public static Supplier<BiomeCatalog.Snapshot<Holder<Biome>>> classificationSource(RegistryAccess access) {
        Registry<Biome> registry=access.registryOrThrow(Registries.BIOME);
        return () -> loader(registry).classificationSnapshot();
    }
    public static synchronized void release(RegistryAccess access) {
        access.registry(Registries.BIOME).ifPresent(registry -> {
            LOADERS.remove(registry);
            PENDING.entrySet().removeIf(item -> item.getValue().registry == registry);
        });
    }
    /** Called only for SERVER_DATA_LOAD after aggregate reload and tag binding succeed. */
    public static synchronized void activate(RegistryAccess access) {
        Pending pending = PENDING.remove(access);
        if (pending == null) return;
        pending.loader.activate(pending.proposal);
        var current = pending.loader.snapshot();
        LOG.info("Biome catalog proposal {} after aggregate reload: active={}, entries={}, ids={}",
            pending.proposal.accepted() ? "accepted" : "rejected; retained previous snapshot",
            current.active(), current.entries().size(), current.entries().stream().map(c -> c.definition().id()).toList());
        LOG.info("Biome catalog classification after aggregate reload: enabled={}, entries={}",
            pending.loader.publication().enabled(),pending.loader.classificationSnapshot().entries().size());
        // Do not delete another cycle's pending proposal: Minecraft can overlap
        // reload futures. Failed cycles cannot match a later tags event; their
        // weak keys expire with that failed reload's resources/context.
    }
    public static SimplePreparableReloadListener<Prepared> listener(RegistryAccess access, Set<String> loadedMods) {
        Registry<Biome> registry = access.registryOrThrow(Registries.BIOME);
        CatalogClassificationState<Holder<Biome>> loader = loader(registry);
        Set<String> mods = Set.copyOf(loadedMods);
        return new SimplePreparableReloadListener<>() {
            @Override
            protected Prepared prepare(ResourceManager manager, ProfilerFiller profiler) {
                List<BiomeCatalogLoader.Resource> resources = new ArrayList<>();
                // listResources resolves the highest-priority whole resource BEFORE parsing;
                // invalid shadowed resources are intentionally not inspected.
                var winners = manager.listResources(DIRECTORY.substring(0, DIRECTORY.length() - 1), id -> id.getPath().endsWith(".json"));
                winners.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(ResourceLocation::toString))).forEach(item -> {
                    ResourceLocation file = item.getKey();
                    String path = file.getPath().substring(DIRECTORY.length(), file.getPath().length() - 5);
                    String id = file.getNamespace() + ":" + path;
                    String json=read(item.getValue(),id);
                    resources.add(new BiomeCatalogLoader.Resource(id, item.getValue().sourcePackId(), 0, json));
                });
                var activation=manager.getResource(ACTIVATION).map(resource -> new BiomeCatalogLoader.Resource(
                    ACTIVATION.toString(),resource.sourcePackId(),0,read(resource,ACTIVATION.toString())));
                return new Prepared(resources,activation);
            }
            @Override
            protected void apply(Prepared prepared, ResourceManager manager, ProfilerFiller profiler) {
                var result = loader.propose(prepared.resources(),prepared.activation(), mods, id -> registry.getHolder(ResourceLocation.parse(id)).map(h -> (Holder<Biome>)h));
                for (var diagnostic : result.diagnostics()) {
                    String message = "Biome catalog " + diagnostic.id() + " pack " + diagnostic.pack() + " " + diagnostic.pointer() + ": " + diagnostic.reason();
                    switch (diagnostic.severity()) {
                        case ERROR -> LOG.error(message);
                        case WARNING -> LOG.warn(message);
                        case INFO -> LOG.info(message);
                    }
                }
                synchronized (BiomeCatalogRuntime.class) { PENDING.put(access, new Pending(registry, loader, result)); }
                LOG.info("Biome catalog staged {} proposal; awaiting aggregate reload (access={}, biomeRegistry={}, identityEquals={}/{})",
                    result.accepted() ? "valid" : "invalid", System.identityHashCode(access), System.identityHashCode(registry),
                    identityEquals(access), identityEquals(registry));
            }
        };
    }
    private static String read(net.minecraft.server.packs.resources.Resource resource,String id) {
        try (Reader reader=resource.openAsReader()) {
            StringBuilder text=new StringBuilder(); char[] buffer=new char[4096]; int count;
            while ((count=reader.read(buffer))!=-1) text.append(buffer,0,count);
            return text.toString();
        } catch (IOException e) {
            LOG.error("Biome catalog {} pack {}: cannot read resource; proposal will be rejected",id,resource.sourcePackId(),e);
            return ""; // A read failure rejects the pair; it never acts as absent activation.
        }
    }
    private static boolean identityEquals(Object value) {
        try { return value.getClass().getMethod("equals", Object.class).getDeclaringClass() == Object.class; }
        catch (NoSuchMethodException impossible) { throw new AssertionError(impossible); }
    }
}
