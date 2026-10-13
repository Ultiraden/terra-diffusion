package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Owning timing, identity, retention and concurrent atomic-read contracts without Minecraft. */
public final class WorldClassificationPublicationTest {
    private static long checks;
    private static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
    private static void rejects(Runnable action,String reason){boolean thrown=false;try{action.run();}catch(IllegalArgumentException|IllegalStateException e){thrown=true;}check(thrown,reason);}
    private static Candidate<Object> entry(String id,String biome,Object holder){
        return new Candidate<>(new Entry(id,biome,Set.of(),Set.of(Environment.land),Map.of(),Map.of(),new Selection(1,0,false)),holder);
    }
    @SafeVarargs private static Snapshot<Object> catalog(Candidate<Object>...entries){return new Snapshot<>(true,List.of(entries));}
    private static BiomePalette<Object> palette(List<String> ids,Map<String,Object> holders){return new BiomePalette<>(ids,id->Optional.ofNullable(holders.get(id)));}
    public static void main(String[] args)throws Exception{
        Object a=new Object(),b=new Object(),c=new Object();Map<String,Object> registry=Map.of("fixture:a",a,"fixture:b",b,"fixture:c",c);
        var ids=List.of("fixture:a","fixture:b");var policy=SavedSelectionPolicy.generic(1024,ids);var palette=palette(ids,registry);
        check(palette.at(0).id().equals("fixture:a")&&palette.at(1).biome()==b,"ASCII index and exact resolved identity");
        palette.verifyOwnership(id->Optional.ofNullable(registry.get(id)));checks++;
        rejects(()->palette.verifyOwnership(id->Optional.of(new Object())),"same text/different registry identity rejected");
        rejects(()->palette(List.of("fixture:a","fixture:missing"),registry),"missing early palette holder rejected");
        rejects(()->SavedSelectionPolicy.generic(1024,List.of("fixture:b","fixture:a")),"unsorted saved pool rejected");
        rejects(()->SavedSelectionPolicy.generic(1024,List.of("fixture:a","fixture:a")),"duplicate saved pool rejected");
        rejects(()->SavedSelectionPolicy.generic(1024,List.of("fixture:../bad")),"relative path rejected");
        rejects(()->SavedSelectionPolicy.generic(1024,List.of("fixture:é")),"non-ASCII resource rejected");
        check(SavedSelectionPolicy.generic(1024,List.of("f:"+"a".repeat(510))).possibleBiomeIds().getFirst().length()==512,"new saved-pool ID bound exact endpoint");
        rejects(()->SavedSelectionPolicy.generic(1024,List.of("f:"+"a".repeat(511))),"new saved-pool ID memory bound enforced; v1 unchanged");
        rejects(()->new SavedSelectionPolicy(SavedSelectionPolicy.Mode.GENERIC,"future-v99",1024,ids),"unknown saved algorithm rejected");
        rejects(()->new SavedSelectionPolicy(SavedSelectionPolicy.Mode.LEGACY,"cellular-v1",1024,ids),"legacy cannot imply migration");
        check(SavedSelectionPolicy.legacy().equals(SavedSelectionPolicy.legacy()),"missing saved fields stable legacy default");
        var first=catalog(entry("catalog:first","fixture:a",a));var second=catalog(entry("catalog:second","fixture:b",b));var outside=catalog(entry("catalog:outside","fixture:c",c));
        var scaleSix=new RegistryClassificationController<Object>();var six=scaleSix.bind(-1,6,SavedSelectionPolicy.legacy(),null);
        check(scaleSix.commit(first,true).accepted()&&six.get().worldScale()==6&&six.get().catalog()==first,"legacy Scale6 binding and activation preserved");
        var oldSix=six.get();check(scaleSix.commit(second,true).accepted()&&six.get().catalog()==second&&oldSix.catalog()==first,"legacy Scale6 reload/captured old catalog preserved");
        six.get().verifyProvider(-1,6);checks++;
        six.get().verifyProvider(9123456789L,2);checks++;
        check(scaleSix.bind(0,6,policy,palette).get().worldScale()==6,"generic scale route supports existing1..6 while initial world acceptance remains1/2");
        rejects(()->scaleSix.bind(0,7,SavedSelectionPolicy.legacy(),null),"invalid scale above accepted maximum");
        var early=new RegistryClassificationController<Object>();check(early.commit(first,true).accepted(),"catalog before world binding allowed");
        long fullSeed=-81985529216486895L;var handle=early.bind(fullSeed,2,policy,palette);var captured=handle.get();
        check(captured.genericActive()&&captured.catalog()==first&&captured.rawCatalog()==first,"initial binding consumes current accepted revision");
        check(captured.worldSeed()==fullSeed&&captured.worldScale()==2&&captured.policy().spacingBlocks()==1024,"full-width negative seed and saved scale/spacing retained");
        var context=new BiomeCatalogFilter.Values(Environment.land,Map.of(),Map.of());var scratch=captured.selector().scratch(-1,-1,1,1);
        check(captured.selector().select(context,0,0,scratch).biome().biome()==a,"generic chosen index retains actual owning holder");
        rejects(()->captured.verifyProvider(fullSeed+1,2),"incompatible provider seed rejected");rejects(()->captured.verifyProvider(fullSeed,1),"incompatible provider scale rejected");
        var oldState=early.state();check(!early.commit(outside,false).accepted(),"disabled out-of-pool proposal still rejected");
        check(early.state()==oldState&&handle.get()==captured&&early.state().catalog()==first,"whole catalog/session/activation retained on failure");
        check(!early.commit(catalog(entry("catalog:alias","fixture:a",new Object())),true).accepted(),"catalog same-ID foreign holder rejected");
        check(early.state()==oldState,"foreign holder failure retains exact old atomic record");
        check(early.reject(List.of("aggregate later listener failed")).state()==oldState&&early.state()==oldState,"aggregate failure has no publication mutation");
        check(early.commit(second,true).accepted()&&handle.get()!=captured&&handle.get().catalog()==second,"later accepted reload affects later capture");
        check(captured.catalog()==first&&captured.palette()==palette&&captured.selector().select(context,0,0,scratch).biome().biome()==a,"old pending/cached capture keeps original catalog/program/palette");
        var beforeWorld=new RegistryClassificationController<Object>();var h=beforeWorld.bind(0,1,policy,palette);
        check(!h.get().catalog().active()&&h.get().selector()==null,"binding before catalog is explicit inactive");
        check(beforeWorld.commit(first,true).accepted()&&h.get().genericActive(),"later initial catalog activates already-bound source");
        var invalidInitial=new RegistryClassificationController<Object>();invalidInitial.commit(outside,true);var bad=invalidInitial.bind(0,1,policy,palette);
        check(!bad.get().catalog().active()&&!bad.get().diagnostics().isEmpty()&&invalidInitial.state().catalog()==outside,"initial semantic failure inactive with diagnosable metadata");
        check(invalidInitial.commit(first,true).accepted()&&bad.get().genericActive(),"valid later proposal recovers inactive initial session");
        var empty=catalog();check(early.commit(empty,true).accepted()&&handle.get().genericActive(),"enabled valid empty catalog remains active");
        var emptyCapture=handle.get();var emptyScratch=emptyCapture.selector().scratch(0,0,1,1);
        check(emptyCapture.selector().select(context,0,0,emptyScratch)==null&&emptyScratch.noMatch()==CoherentBiomeSelector.NoMatch.NO_ELIGIBLE_POSITIVE_CANDIDATE,"empty generic catalog returns typed no-match");
        check(early.commit(first,false).accepted()&&!handle.get().catalog().active()&&handle.get().rawCatalog()==first,"marker false preserves metadata, disables classifier");
        var legacy=early.bind(0,1,SavedSelectionPolicy.legacy(),null);
        check(early.commit(second,true).accepted()&&legacy.get().catalog()==second&&legacy.get().selector()==null&&!legacy.get().policy().generic(),"legacy binding remains legacy with v1 enabled marker");
        var narrow=early.bind(1,1,SavedSelectionPolicy.generic(2048,List.of("fixture:a")),palette(List.of("fixture:a"),registry));
        check(!narrow.get().catalog().active(),"second initial pool incompatibility diagnosed");
        var allOld=early.state();check(!early.commit(second,true).accepted()&&early.state()==allOld,"one incompatible source rejects complete controller commit");
        check(early.commit(first,true).accepted()&&narrow.get().genericActive(),"atomic multiple source activation with in-pool target");
        var foreign=new RegistryClassificationController<Object>();var foreignHandle=foreign.bind(1,1,SavedSelectionPolicy.legacy(),null);
        rejects(()->early.release(foreignHandle),"release cannot remove another controller binding");
        early.release(narrow);rejects(narrow::get,"released handle cannot capture future session");
        check(captured.palette().at(0).biome()==a,"already captured immutable palette survives release");
        // Concurrent readers inspect a SINGLE State, never separately read pointers.
        AtomicReference<Throwable> failure=new AtomicReference<>();Thread reader=new Thread(()->{
            try{for(int i=0;i<10000;i++){var observed=early.state();for(var p:observed.worlds().values()){
                if(p.rawCatalog()!=observed.catalog()||p.revision()!=observed.revision()||p.catalog().active()!=observed.enabled())throw new AssertionError("torn publication");
            }}}catch(Throwable t){failure.set(t);}
        });reader.start();for(int i=0;i<1000;i++)check(early.commit((i&1)==0?first:second,(i&3)!=0).accepted(),"concurrent in-pool commit");reader.join();check(failure.get()==null,"metadata and all session programs read atomically");
        early.release(handle);early.release(legacy);check(early.state().worlds().isEmpty(),"release clears source tokens only");
        check(early.state().catalog().active(),"ordinary metadata remains after source release");
        System.out.println("PASS "+checks+" owning publication checks; no Minecraft/provider/native execution");
    }
}
