package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import java.util.regex.Pattern;

/** Saved source choice. No provider, registry, platform or global configuration access. */
public record SavedSelectionPolicy(Mode mode,String algorithm,int spacingBlocks,List<String> possibleBiomeIds,Routing routing) {
    public enum Mode { LEGACY, GENERIC }
    public enum Routing { COMPATIBILITY, CATALOG_ONLY }
    /** Source compatibility for callers of the accepted v1 saved policy. */
    public SavedSelectionPolicy(Mode mode,String algorithm,int spacingBlocks,List<String> ids){
        this(mode,algorithm,spacingBlocks,ids,Routing.COMPATIBILITY);
    }
    public static final int MAX_POOL_IDS=4096;
    /** Bounds only the new saved pool, not schema-v1 catalog IDs or legacy worlds. */
    public static final int MAX_SAVED_BIOME_ID_CHARS=512;
    private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    public SavedSelectionPolicy {
        Objects.requireNonNull(mode);Objects.requireNonNull(algorithm);Objects.requireNonNull(possibleBiomeIds);Objects.requireNonNull(routing);
        possibleBiomeIds=List.copyOf(possibleBiomeIds);
        if(mode==Mode.LEGACY){
            if(!algorithm.isEmpty()||!possibleBiomeIds.isEmpty()||spacingBlocks!=CoherentBiomeSelector.DEFAULT_SPACING||routing!=Routing.COMPATIBILITY)
                throw new IllegalArgumentException("legacy policy has no generic configuration");
        }else{
            if(!CoherentBiomeSelector.ALGORITHM.equals(algorithm))throw new IllegalArgumentException("unsupported selector algorithm: "+algorithm);
            new CoherentBiomeSelector.Config(spacingBlocks);
            if(possibleBiomeIds.isEmpty()||possibleBiomeIds.size()>MAX_POOL_IDS)throw new IllegalArgumentException("invalid saved possible-biome pool size");
            String previous=null;
            for(String id:possibleBiomeIds){
                if(!validId(id))throw new IllegalArgumentException("invalid saved biome ID: "+id);
                if(previous!=null&&previous.compareTo(id)>=0)throw new IllegalArgumentException("saved biome IDs must be unique ASCII order");
                previous=id;
            }
        }
    }
    public static SavedSelectionPolicy legacy(){return new SavedSelectionPolicy(Mode.LEGACY,"",CoherentBiomeSelector.DEFAULT_SPACING,List.of());}
    public static SavedSelectionPolicy generic(int spacing,List<String> ids){return new SavedSelectionPolicy(Mode.GENERIC,CoherentBiomeSelector.ALGORITHM,spacing,ids);}
    public static SavedSelectionPolicy catalogOnly(int spacing,List<String> ids){return new SavedSelectionPolicy(Mode.GENERIC,CoherentBiomeSelector.ALGORITHM,spacing,ids,Routing.CATALOG_ONLY);}
    public boolean generic(){return mode==Mode.GENERIC;}
    public boolean catalogOnly(){return routing==Routing.CATALOG_ONLY;}
    public static boolean validId(String id){
        return id!=null&&id.length()<=MAX_SAVED_BIOME_ID_CHARS&&ID.matcher(id).matches()&&Arrays.stream(id.substring(id.indexOf(':')+1).split("/")).noneMatch(p->p.equals(".")||p.equals(".."));
    }
}
