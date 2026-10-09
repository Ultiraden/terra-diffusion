package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import java.util.function.Supplier;

/** Explicit classification binding. Each enqueue captures one immutable value;
 * later publication/binding changes cannot change an already queued tile. This
 * changes neither tile keys nor cache invalidation/worker lifetime semantics. */
public final class CatalogSnapshotSource {
    private static final BiomeCatalog.Snapshot<?> INACTIVE=new BiomeCatalog.Snapshot<>(false,List.of());
    private static final Supplier<BiomeCatalog.Snapshot<?>> LEGACY=() -> INACTIVE;
    private volatile Supplier<? extends BiomeCatalog.Snapshot<?>> supplier=LEGACY;
    private final Map<BiomeCatalog.Snapshot<?>,Set<String>> reported=new WeakHashMap<>();
    public synchronized void bind(Supplier<? extends BiomeCatalog.Snapshot<?>> supplier) { this.supplier=Objects.requireNonNull(supplier); reported.clear(); }
    public synchronized void clear() { supplier=LEGACY; reported.clear(); }
    public BiomeCatalog.Snapshot<?> capture() { return Objects.requireNonNull(supplier.get(),"catalog snapshot supplier returned null"); }
    /** Diagnostics only, independent of generation/cache identity. */
    public synchronized boolean firstDiagnostic(BiomeCatalog.Snapshot<?> snapshot,String key) {
        return reported.computeIfAbsent(snapshot,ignored -> new HashSet<>()).add(key);
    }
}
