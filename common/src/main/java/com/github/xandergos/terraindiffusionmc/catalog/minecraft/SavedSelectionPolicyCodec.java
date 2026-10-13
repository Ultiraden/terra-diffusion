package com.github.xandergos.terraindiffusionmc.catalog.minecraft;

import com.github.xandergos.terraindiffusionmc.catalog.SavedSelectionPolicy;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

/** Generic-only saved codec. An absent source field is the legacy policy. */
public final class SavedSelectionPolicyCodec {
    private record Fields(int version,String algorithm,int spacing,List<String> pool) {}
    private static final Codec<Fields> FIELDS=RecordCodecBuilder.create(i->i.group(
        Codec.INT.fieldOf("version").forGetter(Fields::version),
        Codec.STRING.fieldOf("algorithm").forGetter(Fields::algorithm),
        Codec.INT.fieldOf("spacing_world_blocks").forGetter(Fields::spacing),
        Codec.STRING.listOf().fieldOf("possible_biomes").forGetter(Fields::pool)
    ).apply(i,Fields::new));
    public static final Codec<SavedSelectionPolicy> CODEC=FIELDS.comapFlatMap(f->{
        try {
            if(f.version()!=1)throw new IllegalArgumentException("unsupported saved selection version");
            return DataResult.success(new SavedSelectionPolicy(SavedSelectionPolicy.Mode.GENERIC,f.algorithm(),f.spacing(),f.pool()));
        }catch(IllegalArgumentException e){return DataResult.error(e::getMessage);}
    },p->new Fields(1,p.algorithm(),p.spacingBlocks(),p.possibleBiomeIds()));
    private SavedSelectionPolicyCodec() {}
}
