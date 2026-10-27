package com.github.xandergos.terraindiffusionmc.catalog.minecraft;

import com.github.xandergos.terraindiffusionmc.catalog.SavedSelectionPolicy;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;

/** Generic-only saved codec. An absent source field is the legacy policy. */
public final class SavedSelectionPolicyCodec {
    private record Fields(int version,String algorithm,int spacing,List<String> pool,Optional<String> routing) {}
    private static final Codec<Fields> FIELDS=RecordCodecBuilder.create(i->i.group(
        Codec.INT.fieldOf("version").forGetter(Fields::version),
        Codec.STRING.fieldOf("algorithm").forGetter(Fields::algorithm),
        Codec.INT.fieldOf("spacing_world_blocks").forGetter(Fields::spacing),
        Codec.STRING.listOf().fieldOf("possible_biomes").forGetter(Fields::pool),
        Codec.STRING.optionalFieldOf("routing").forGetter(Fields::routing)
    ).apply(i,Fields::new));
    public static final Codec<SavedSelectionPolicy> CODEC=FIELDS.comapFlatMap(f->{
        try {
            SavedSelectionPolicy.Routing routing;
            if(f.version()==1&&f.routing().isEmpty())routing=SavedSelectionPolicy.Routing.COMPATIBILITY;
            else if(f.version()==2&&f.routing().orElse("").equals("catalog-only-v1"))routing=SavedSelectionPolicy.Routing.CATALOG_ONLY;
            else throw new IllegalArgumentException("unsupported saved selection version/routing");
            return DataResult.success(new SavedSelectionPolicy(SavedSelectionPolicy.Mode.GENERIC,f.algorithm(),f.spacing(),f.pool(),routing));
        }catch(IllegalArgumentException e){return DataResult.error(e::getMessage);}
    },p->new Fields(p.catalogOnly()?2:1,p.algorithm(),p.spacingBlocks(),p.possibleBiomeIds(),p.catalogOnly()?Optional.of("catalog-only-v1"):Optional.empty()));
    private SavedSelectionPolicyCodec() {}
}
