package com.github.xandergos.terraindiffusionmc.catalog;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** Immutable source-owned integer palette. B is an exact owning holder in Minecraft. */
public final class BiomePalette<B> {
    public record Entry<B>(int index,String id,B biome) {}
    private final List<Entry<B>> entries;
    private final Map<String,Entry<B>> byId;
    private final String id;
    public BiomePalette(List<String> ids,Function<String,Optional<B>> resolver){
        // Use the same strict saved-pool normalization contract without choosing a runtime policy.
        SavedSelectionPolicy.generic(CoherentBiomeSelector.DEFAULT_SPACING,ids);
        Objects.requireNonNull(resolver);List<Entry<B>> ordered=new ArrayList<>();Map<String,Entry<B>> indexed=new HashMap<>();
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String name:ids){
                B biome=resolver.apply(name).orElseThrow(()->new IllegalArgumentException("missing owning biome "+name));
                Entry<B> entry=new Entry<>(ordered.size(),name,biome);ordered.add(entry);indexed.put(name,entry);
                digest.update(name.getBytes(StandardCharsets.US_ASCII));digest.update((byte)0);
            }
            id=HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        entries=List.copyOf(ordered);byId=Map.copyOf(indexed);
    }
    /** Text identity describes indexes; object/holder identity still proves owning registry. */
    public String id(){return id;}
    public int size(){return entries.size();}
    public List<Entry<B>> entries(){return entries;}
    public Entry<B> at(int index){return entries.get(index);}
    public Optional<Entry<B>> find(String name){return Optional.ofNullable(byId.get(name));}
    public Entry<B> require(String name){Entry<B> entry=byId.get(name);if(entry==null)throw new IllegalArgumentException("biome outside saved pool: "+name);return entry;}
    public void verifyOwnership(Function<String,Optional<B>> resolver){
        for(Entry<B> entry:entries)if(resolver.apply(entry.id()).orElse(null)!=entry.biome())
            throw new IllegalArgumentException("different owning holder for "+entry.id());
    }
}
