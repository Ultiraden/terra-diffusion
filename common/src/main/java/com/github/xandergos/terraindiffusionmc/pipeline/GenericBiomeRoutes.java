package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.BiomePalette;

/** Owning-pool fallback compatibility, independent of process-global TerralithCompat. */
public final class GenericBiomeRoutes<B> {
    public static final short[] FALLBACK_IDS={1,7,11,3,5,6,8,15,16,17,19,23,26,29,31,32,33,35,41,44,46,48,108,115,116};
    private final int[] indexes=new int[512];
    private final boolean terralith;
    public GenericBiomeRoutes(BiomePalette<B> palette){
        java.util.Arrays.fill(indexes,-1);
        for(short id:FALLBACK_IDS)indexes[id]=palette.require(BiomeDebugTrace.biomeName(id)).index();
        boolean all=true;
        for(var item:TerralithBiomeIds.paths().entrySet()) {
            var entry=palette.find("terralith:"+item.getValue());
            if(entry.isPresent())indexes[item.getKey()]=entry.get().index();else all=false;
        }
        terralith=all;
        for(int i=0;i<3;i++)palette.require(CaveBiomes.IDS[i]);
        long caves=java.util.Arrays.stream(CaveBiomes.IDS).skip(3).filter(id->palette.find(id).isPresent()).count();
        if(caves!=0&&caves!=CaveBiomes.IDS.length-3)throw new IllegalArgumentException("saved pool has partial Terralith cave routes");
    }
    public boolean terralith(){return terralith;}
    public int index(short legacyId){
        if(legacyId<0||legacyId>=indexes.length||indexes[legacyId]<0)throw new IllegalStateException("fallback outside owning palette: "+legacyId);
        return indexes[legacyId];
    }
}
