package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import java.util.concurrent.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogLoader.*;

/** Activation, catalog and queued-input contracts independent of Minecraft.
 * Winning-resource precedence and owning lifecycle binding need runtime tests. */
public final class CatalogClassificationStateTest {
    private static int checks;
    private static final String ENTRY="{\"schema_version\":1,\"biome\":\"fixture:registered\",\"eligibility\":{\"environments\":[\"land\"]}}";
    private static final String ENABLED="{\"schema_version\":1,\"enabled\":true}";
    private static final String DISABLED="{\"schema_version\":1,\"enabled\":false}";
    private static void check(boolean value,String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static Resource entry(String id) { return new Resource(id,"fixture",0,ENTRY); }
    private static Optional<Resource> marker(String json) { return Optional.of(new Resource(CatalogClassificationState.ACTIVATION_RESOURCE,"fixture",0,json)); }
    private static CatalogClassificationState.Proposal<String> propose(CatalogClassificationState<String> state,List<Resource> resources,Optional<Resource> marker) {
        return state.propose(resources,marker,Set.of(),Optional::of);
    }
    public static void main(String[] args) throws Exception {
        CatalogClassificationState<String> state=new CatalogClassificationState<>();
        check(!state.snapshot().active() && !state.classificationSnapshot().active(),"initial publication is inactive");
        var noMarker=propose(state,List.of(entry("fixture:a")),Optional.empty());
        check(noMarker.accepted() && !state.snapshot().active(),"valid nonempty catalog preparation has no side effects");
        state.activate(noMarker);
        check(state.snapshot().active() && state.snapshot().entries().size()==1 && !state.classificationSnapshot().active(),"nonempty catalog without activation retains legacy");
        var enabled=propose(state,List.of(entry("fixture:a")),marker(ENABLED));
        state.activate(enabled);
        var before=state.publication(); var queued=state.classificationSnapshot();
        check(before.enabled() && before.catalog()==before.classification(),"enabled snapshot and activation are one publication");
        var validDisable=propose(state,List.of(entry("fixture:b")),marker(DISABLED));
        check(state.publication()==before,"staged disable/new catalog waits for aggregate success");
        // The owner discards this proposal after an unrelated aggregate listener
        // fails; preparing another invalid cycle must not publish either value.
        var invalidMarker=propose(state,List.of(entry("fixture:b")),marker("{\"schema_version\":1,\"enabled\":\"false\"}"));
        check(!invalidMarker.accepted() && invalidMarker.catalog().accepted(),"valid catalog plus malformed activation rejected");
        state.activate(invalidMarker);
        check(state.publication()==before,"invalid activation retains both previous values");
        var invalidCatalog=propose(state,List.of(new Resource("fixture:bad","fixture",0,"{}")),marker(DISABLED));
        state.activate(invalidCatalog);
        check(!invalidCatalog.accepted() && state.publication()==before,"malformed catalog cannot disable previous classification");
        state.activate(validDisable);
        check(state.snapshot().byId("fixture:b").isPresent() && !state.classificationSnapshot().active(),"explicit false publishes complete disable/catalog pair");
        check(queued.active() && queued.byId("fixture:a").isPresent(),"already captured tile keeps enabled original input");
        state.activate(propose(state,List.of(entry("fixture:b")),marker(ENABLED)));
        check(state.classificationSnapshot().active() && state.classificationSnapshot().byId("fixture:b").isPresent(),"explicit true re-enables current catalog");
        state.activate(propose(state,List.of(),marker(ENABLED)));
        check(state.publication().enabled() && state.classificationSnapshot().active() && state.classificationSnapshot().entries().isEmpty(),"valid enabled empty remains active for deterministic fallback");
        state.activate(propose(state,List.of(),Optional.empty()));
        check(!state.publication().enabled() && state.snapshot().active(),"removing marker explicitly returns later tiles to legacy");
        CatalogClassificationState<String> initial=new CatalogClassificationState<>();
        initial.activate(propose(initial,List.of(entry("fixture:a")),marker("{}")));
        check(!initial.snapshot().active() && !initial.classificationSnapshot().active(),"initial malformed activation leaves entire pair inactive");
        initial.activate(propose(initial,List.of(new Resource("fixture:bad","fixture",0,"{}")),marker(ENABLED)));
        check(!initial.snapshot().active() && !initial.classificationSnapshot().active(),"initial malformed catalog cannot activate classification");
        for (String bad:List.of("{}","[]","null","{","{\"schema_version\":2,\"enabled\":true}",
            "{\"schema_version\":\"1\",\"enabled\":true}","{\"schema_version\":1}",
            "{\"schema_version\":1,\"enabled\":0}","{\"schema_version\":1,\"enabled\":null}",
            "{\"schema_version\":1,\"enabled\":true,\"extra\":true}",
            "{\"schema_version\":1,\"enabled\":true,\"enabled\":false}",
            "{\"schema_version\":1,/*comment*/\"enabled\":true}")) {
            try { CatalogClassificationState.parseActivation(bad); throw new AssertionError("malformed marker accepted: "+bad); }
            catch (IllegalArgumentException expected) { check(expected.getMessage().contains(": "),"activation error has diagnostic pointer"); }
        }
        check(CatalogClassificationState.parseActivation(ENABLED) && !CatalogClassificationState.parseActivation(DISABLED),"strict marker booleans");
        var failed=propose(state,List.of(new Resource("fixture:bad","fixture",0,"{}")),marker("{}"));
        check(failed.diagnostics().size()==2 && failed.diagnostics().stream().map(Diagnostic::id).toList().equals(
            failed.diagnostics().stream().map(Diagnostic::id).sorted().toList()),"catalog and activation validation errors retained in ASCII order");
        // One immutable publication prevents a reader combining a newly enabled
        // flag with an older catalog during concurrent aggregate completion.
        var disabledA=propose(state,List.of(entry("fixture:a")),marker(DISABLED));
        var enabledB=propose(state,List.of(entry("fixture:b")),marker(ENABLED));
        ExecutorService worker=Executors.newSingleThreadExecutor();
        state.activate(disabledA);
        try {
            Future<?> writer=worker.submit(()->{ for(int i=0;i<10000;i++) { state.activate(enabledB); state.activate(disabledA); } });
            for(int i=0;i<10000;i++) {
                var pair=state.publication();
                if (pair.enabled() ? !pair.catalog().byId("fixture:b").isPresent() || pair.classification()!=pair.catalog()
                                   : !pair.catalog().byId("fixture:a").isPresent() || !pair.classification().entries().isEmpty())
                    throw new AssertionError("mixed catalog/activation publication");
            }
            writer.get(10,TimeUnit.SECONDS); checks++;
        } finally { worker.shutdownNow(); }
        System.out.println("PASS: atomic catalog classification activation checks="+checks);
    }
}
