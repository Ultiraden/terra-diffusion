package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** One captured owning decision program; tiles may retain it through reload/release. */
public record WorldClassificationPublication<B>(long worldSeed,int worldScale,SavedSelectionPolicy policy,
        BiomePalette<B> palette,Snapshot<B> rawCatalog,Snapshot<B> catalog,
        CoherentBiomeSelector<BiomePalette.Entry<B>> selector,long revision,List<String> diagnostics) {
    public WorldClassificationPublication {
        // Preserve accepted WorldScaleManager's full 1..6 support. The initial
        // generic acceptance maps cover 1/2; that does not narrow saved profiles.
        if(worldScale<1||worldScale>6)throw new IllegalArgumentException("supported saved world scale is 1 through 6");
        Objects.requireNonNull(policy);Objects.requireNonNull(rawCatalog);Objects.requireNonNull(catalog);diagnostics=List.copyOf(diagnostics);
        if(policy.generic()&&palette==null)throw new IllegalArgumentException("generic publication requires an owning palette");
        if(policy.generic()&&catalog.active()&&selector==null)throw new IllegalArgumentException("active generic publication requires a compiled selector");
        if(!policy.generic()&&selector!=null)throw new IllegalArgumentException("legacy publication cannot have a generic selector");
    }
    public boolean genericActive(){return policy.generic()&&catalog.active();}
    public void verifyProvider(long seed,int scale){
        // Legacy explorer/live-scale semantics predate this saved generic policy.
        if(policy.generic()&&(seed!=worldSeed||scale!=worldScale))throw new IllegalStateException("provider differs from captured generic owning seed/scale");
    }
    static <B> WorldClassificationPublication<B> build(long seed,int scale,SavedSelectionPolicy policy,
            BiomePalette<B> palette,Snapshot<B> raw,boolean enabled,long revision,boolean rejectInvalid){
        List<String> errors=new ArrayList<>();List<Candidate<BiomePalette.Entry<B>>> indexed=new ArrayList<>();
        if(policy.generic()){
            if(palette==null||!palette.entries().stream().map(BiomePalette.Entry::id).toList().equals(policy.possibleBiomeIds()))
                throw new IllegalArgumentException("saved policy/palette mismatch");
            for(Candidate<B> candidate:raw.entries()){
                var entry=palette.find(candidate.definition().biome());
                if(entry.isEmpty())errors.add(candidate.definition().id()+": target "+candidate.definition().biome()+" outside saved pool "+palette.id());
                else if(entry.get().biome()!=candidate.biome())errors.add(candidate.definition().id()+": different owning holder for "+candidate.definition().biome());
                else indexed.add(new Candidate<>(candidate.definition(),entry.get()));
            }
        }
        errors.sort(String::compareTo);
        if(rejectInvalid&&!errors.isEmpty())throw new IllegalArgumentException(String.join("; ",errors));
        Snapshot<B> classification=enabled&&errors.isEmpty()?raw:new Snapshot<>(false,List.of());
        CoherentBiomeSelector<BiomePalette.Entry<B>> selector=policy.generic()&&classification.active()
                ?new CoherentBiomeSelector<>(new Snapshot<>(true,indexed),seed,new CoherentBiomeSelector.Config(policy.spacingBlocks())):null;
        return new WorldClassificationPublication<>(seed,scale,policy,palette,raw,classification,selector,revision,errors);
    }
}
