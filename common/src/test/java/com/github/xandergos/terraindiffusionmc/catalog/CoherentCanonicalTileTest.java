package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Whole production-shaped boundary tiles, not isolated world-edge point checks. */
public final class CoherentCanonicalTileTest {
    private static long checks;
    private static void check(boolean v,String reason){checks++;if(!v)throw new AssertionError(reason);}
    private static void rejects(Runnable r){boolean thrown=false;try{r.run();}catch(IllegalArgumentException e){thrown=true;}check(thrown,"invalid internal spill request rejected");}
    public static void main(String[] args){
        List<Candidate<String>> entries=new ArrayList<>();for(int i=0;i<3;i++)entries.add(new Candidate<>(new Entry("fixture:e"+i,"fixture:biome"+i,Set.of(),Set.of(Environment.land),Map.of(),Map.of(),new Selection(i+1,0,false)),"resolved:"+i));
        var selector=new CoherentBiomeSelector<>(new Snapshot<>(true,entries),-81985529216486895L,CoherentBiomeSelector.Config.defaults());
        var context=new BiomeCatalogFilter.Values(Environment.land,Map.of(),Map.of());long limit=CoherentBiomeSelector.WORLD_LIMIT;
        long negative=Math.floorDiv(-limit,1024)*1024,positive=Math.floorDiv(limit,1024)*1024;
        long valid=0,spill=0;
        for(long x0:new long[]{negative,positive})for(long z0:new long[]{negative,positive}){
            var full=selector.canonicalScratch(x0,z0,1024);
            long minX=Math.max(x0,-limit),minZ=Math.max(z0,-limit),maxX=Math.min(x0+1024,limit+1),maxZ=Math.min(z0+1024,limit+1);
            var clipped=selector.scratch(minX,minZ,maxX,maxZ);check(full.siteCount()==9,"aligned complete boundary tile nine cached sites");
            for(long z=z0;z<z0+1024;z++)for(long x=x0;x<x0+1024;x++){
                var selected=selector.select(context,x,z,full);check(selected!=null,"entire internal tile including spill classifies");
                if(x>=minX&&x<maxX&&z>=minZ&&z<maxZ){check(selected==selector.select(context,x,z,clipped),"every valid column exactly equals strict clipped query");valid++;}else spill++;
            }
            for(long x:new long[]{minX,maxX-1})for(long z:new long[]{minZ,maxZ-1}){
                selector.select(context,x,z,full);var a=full.decision();selector.select(context,x,z,clipped);
                check(a.site().equals(clipped.decision().site()),"boundary site coordinates/quantile exact");
            }
        }
        rejects(()->selector.scratch(positive,0,positive+1024,1024));
        rejects(()->selector.canonicalScratch(1,0,1024));rejects(()->selector.canonicalScratch(0,0,1000));
        rejects(()->selector.canonicalScratch(30000128,0,1024));rejects(()->selector.canonicalScratch(Long.MIN_VALUE,0,1024));
        rejects(()->selector.canonicalScratch(0,0,131072));
        check(valid>0&&spill>0,"both valid and out-of-domain internal columns exercised");
        System.out.println("PASS "+checks+" canonical-boundary checks; valid="+valid+" spill="+spill+"; no inference");
    }
}
