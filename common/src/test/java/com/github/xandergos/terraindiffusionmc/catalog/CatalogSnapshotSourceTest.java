package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Publication/binding can change while a queued task retains its captured
 * immutable input. Actual provider/cache lifecycle is verified in Minecraft. */
public final class CatalogSnapshotSourceTest {
    private static int checks;
    private static final String BASE="{\"schema_version\":1,\"biome\":\"fixture:registered\",\"eligibility\":{\"environments\":[\"land\"]}}";
    private static void check(boolean value,String reason) { checks++; if(!value) throw new AssertionError(reason); }
    private static BiomeCatalogLoader.Resource resource(String id,String json) { return new BiomeCatalogLoader.Resource(id,"fixture",0,json); }
    public static void main(String[] args) throws Exception {
        CatalogSnapshotSource source=new CatalogSnapshotSource(); check(!source.capture().active(),"default legacy binding");
        BiomeCatalogLoader<String> registryA=new BiomeCatalogLoader<>();
        registryA.reload(List.of(resource("fixture:a",BASE)),Set.of(),id->Optional.of("registry-A:"+id));
        source.bind(registryA::snapshot);
        Snapshot<?> enqueued=source.capture(); check(enqueued.active() && enqueued.entries().size()==1,"enqueue captures registry-A proposal");
        ExecutorService worker=Executors.newSingleThreadExecutor(); CountDownLatch begin=new CountDownLatch(1),queued=new CountDownLatch(1);
        try {
            Future<Snapshot<?>> pending=worker.submit(()->{ queued.countDown(); if(!begin.await(10,TimeUnit.SECONDS)) throw new AssertionError("worker gate timeout"); return enqueued; });
            check(queued.await(10,TimeUnit.SECONDS),"task queued before reload");
            registryA.reload(List.of(resource("fixture:b",BASE)),Set.of(),id->Optional.of("registry-A:"+id));
            Snapshot<?> next=source.capture(); check(next!=enqueued && next.entries().getFirst().definition().id().equals("fixture:b"),"later enqueue sees successful replacement");
            begin.countDown(); check(pending.get(10,TimeUnit.SECONDS)==enqueued,"already queued input unaffected by reload");
            registryA.reload(List.of(resource("fixture:bad","{")),Set.of(),Optional::of);
            check(source.capture()==next,"failed proposal retains the captured next input");
            check(source.firstDiagnostic(next,"entry:unavailable"),"first diagnostic admitted");
            check(!source.firstDiagnostic(next,"entry:unavailable"),"same entry diagnostic suppressed");
            BiomeCatalogLoader<String> registryB=new BiomeCatalogLoader<>();
            registryB.reload(List.of(resource("fixture:b",BASE)),Set.of(),id->Optional.of("registry-B:"+id));
            source.bind(registryB::snapshot); Snapshot<?> secondWorld=source.capture();
            check(secondWorld.entries().getFirst().biome().toString().startsWith("registry-B:"),"new binding resolves its own holders");
            check(enqueued.entries().getFirst().biome().toString().startsWith("registry-A:"),"old captured input retains original holder context");
            check(source.firstDiagnostic(secondWorld,"entry:unavailable"),"diagnostics reset for new binding");
            source.clear(); check(!source.capture().active(),"shutdown clears supplier binding");
            source.bind(()->new Snapshot<>(true,List.of())); check(source.capture().active() && source.capture().entries().isEmpty(),"explicit active empty preserved");
            source.bind(()->null);
            try { source.capture(); throw new AssertionError("null snapshot accepted"); } catch(NullPointerException expected) { checks++; }
            source.clear();
        } finally { begin.countDown(); worker.shutdownNow(); }
        System.out.println("PASS: catalog snapshot source checks="+checks);
    }
}
