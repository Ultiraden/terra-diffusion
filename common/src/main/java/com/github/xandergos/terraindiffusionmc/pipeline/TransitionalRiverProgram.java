package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.*;
import java.util.*;

/** Only the approved transitional warm-river bindings; no land/ocean bridge or global compatibility. */
final class TransitionalRiverProgram {
    private final List<BiomeCatalogFilter.Compiled> entries;
    TransitionalRiverProgram(BiomeCatalog.Snapshot<?> catalog){
        Set<String> ids=Set.of(TerralithCatalogBindings.RESOURCES[4]);
        entries=catalog.entries().stream().map(BiomeCatalog.Candidate::definition).filter(e -> ids.contains(e.id()))
            .sorted(Comparator.comparing(BiomeCatalog.Entry::id)).map(BiomeCatalogFilter.Compiled::new).toList();
    }
    BiomeCatalog.Entry choose(BiomeCatalogFilter.Context context){
        for(var e:entries)if(e.definition().biome().equals(TerralithCatalogBindings.BIOMES[4])
            &&e.definition().selection().weight()>0 &&e.eligible(context))return e.definition();
        return null;
    }
}
