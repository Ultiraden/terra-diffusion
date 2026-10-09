package com.github.xandergos.terraindiffusionmc.catalog;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogLoader.*;

/** Registry-owned, atomic catalog/activation publication. Missing metadata keeps
 * classification legacy; a valid enabled empty catalog is still active. */
public final class CatalogClassificationState<B> {
    public static final String ACTIVATION_RESOURCE="terrain-diffusion-mc:terrain_diffusion/catalog_activation.json";
    public record Publication<B>(Snapshot<B> catalog, Snapshot<B> classification) {
        public boolean enabled() { return classification.active(); }
    }
    public record Proposal<B>(Result<B> catalog, boolean activationValid, boolean enabled, List<Diagnostic> diagnostics) {
        public Proposal { diagnostics=List.copyOf(diagnostics); }
        public boolean accepted() { return catalog.accepted() && activationValid; }
    }
    private final BiomeCatalogLoader<B> loader=new BiomeCatalogLoader<>();
    private final Snapshot<B> inactive=new Snapshot<>(false,List.of());
    private volatile Publication<B> publication=new Publication<>(inactive,inactive);
    public Publication<B> publication() { return publication; }
    public Snapshot<B> snapshot() { return publication.catalog(); }
    public Snapshot<B> classificationSnapshot() { return publication.classification(); }

    /** Both inputs validate even when disabled. Never publish during preparation. */
    public Proposal<B> propose(Collection<Resource> resources, Optional<Resource> activation,
                               Set<String> mods, Function<String,Optional<B>> registry) {
        Result<B> catalog=loader.propose(resources,mods,registry);
        List<Diagnostic> diagnostics=new ArrayList<>(catalog.diagnostics());
        boolean valid=true, enabled=false;
        if (activation.isPresent()) {
            Resource resource=activation.get();
            try { enabled=parseActivation(resource.json()); }
            catch (IllegalArgumentException e) {
                valid=false;
                String message=e.getMessage(); int split=message.indexOf(": ");
                diagnostics.add(new Diagnostic(resource.id(),resource.pack(),split<0?"/":message.substring(0,split),
                    Severity.ERROR,split<0?message:message.substring(split+2)));
            }
        }
        diagnostics.sort(Comparator.comparing(Diagnostic::id).thenComparing(Diagnostic::pack)
            .thenComparing(Diagnostic::pointer).thenComparing(Diagnostic::reason));
        return new Proposal<>(catalog,valid,enabled,diagnostics);
    }
    /** Owning runtime calls this only after the aggregate resource reload succeeds. */
    public synchronized void activate(Proposal<B> proposal) {
        if (!proposal.accepted()) return;
        loader.activate(proposal.catalog());
        Snapshot<B> catalog=proposal.catalog().snapshot();
        publication=new Publication<>(catalog,proposal.enabled()?catalog:inactive);
    }
    public static boolean parseActivation(String json) {
        Object value=StrictJson.parse(json);
        if (!(value instanceof Map<?,?> root)) throw invalid("/","expected activation object");
        for (Object key:root.keySet()) if (!Set.of("schema_version","enabled").contains(key))
            throw invalid("/"+key.toString().replace("~","~0").replace("/","~1"),"unknown activation field");
        if (!root.containsKey("schema_version")) throw invalid("/schema_version","required field");
        if (!(root.get("schema_version") instanceof BigDecimal version) || version.compareTo(BigDecimal.ONE)!=0)
            throw invalid("/schema_version","unsupported activation schema version");
        if (!root.containsKey("enabled")) throw invalid("/enabled","required field");
        if (!(root.get("enabled") instanceof Boolean enabled)) throw invalid("/enabled","expected boolean");
        return enabled;
    }
    private static IllegalArgumentException invalid(String pointer,String reason) { return new IllegalArgumentException(pointer+": "+reason); }
}
