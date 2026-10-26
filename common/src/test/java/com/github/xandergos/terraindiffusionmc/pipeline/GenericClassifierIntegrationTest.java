package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** CPU-only production dispatch. Pinned accepted classifier is renamed by the runner, equations unchanged. */
public final class GenericClassifierIntegrationTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static Snapshot<String> load(Path path)throws Exception{
        List<BiomeCatalogLoader.Resource> resources=new ArrayList<>();
        try(var stream=Files.walk(path)){
            for(Path p:stream.filter(f->f.toString().endsWith(".json")).sorted().toList()){
                String relative=path.relativize(p).toString().replace('\\','/');
                resources.add(new BiomeCatalogLoader.Resource("ultira:"+relative.substring(0,relative.length()-5),"test",0,Files.readString(p)));
            }
        }
        var result=new BiomeCatalogLoader<String>().reload(resources,Set.of("terralith"),id->Optional.of(id.intern()));
        check(result.accepted()&&result.snapshot().entries().size()==429,"accepted full429 fixture");return result.snapshot();
    }
    private static List<String> ids(){
        TreeSet<String> ids=new TreeSet<>();
        for(short id:GenericBiomeRoutes.FALLBACK_IDS)ids.add(BiomeDebugTrace.biomeName(id));
        for(String path:TerralithBiomeIds.paths().values())ids.add("terralith:"+path);
        ids.addAll(List.of(CaveBiomes.IDS));return List.copyOf(ids);
    }
    private static WorldClassificationPublication<String> publication(Snapshot<String> catalog,List<String> ids,int scale,boolean enabled){
        BiomePalette<String> palette=new BiomePalette<>(ids,Optional::of);
        // String fixtures retain exact object identity from the loaded candidates.
        Map<String,String> owners=new HashMap<>();for(var c:catalog.entries())owners.put(c.definition().biome(),c.biome());
        palette=new BiomePalette<>(ids,id->Optional.of(owners.getOrDefault(id,id)));
        RegistryClassificationController<String> controller=new RegistryClassificationController<>();
        check(controller.commit(catalog,enabled).accepted(),"early metadata commit");
        return controller.bind(-81985529216486895L,scale,SavedSelectionPolicy.generic(1024,ids),palette).get();
    }
    private static void compareSample(BiomeDebugTrace.Observation a,BiomeDebugTrace.Observation b)throws Exception{
        for(var field:BiomeDebugTrace.Sample.class.getRecordComponents()) {
            Object x=field.getAccessor().invoke(a.sample()),y=field.getAccessor().invoke(b.sample());
            check(x instanceof Float f?Float.floatToRawIntBits(f)==Float.floatToRawIntBits((Float)y):x.equals(y),"raw field "+field.getName());
        }
        check(a.context().equals(b.context())&&a.wet()==b.wet(),"identical environmental view/mask");
    }
    private static void grids(Snapshot<String> catalog)throws Exception{
        Random random=new Random(6006);int h=32,w=32,n=h*w;
        for(int scale:new int[]{1,2,6})for(boolean active:new boolean[]{false,true}){
            float[] elev=new float[n],climate=new float[4*n],halo=new float[(h+2)*(w+2)];boolean[] wet=new boolean[n];
            for(int i=0;i<n;i++){elev[i]=random.nextFloat()*6000-1000;climate[i]=random.nextFloat()*65-25;
                climate[n+i]=random.nextFloat()*2500;climate[2*n+i]=random.nextFloat()*5000;climate[3*n+i]=random.nextFloat()*180;wet[i]=i%11==0;}
            for(int i=0;i<halo.length;i++)halo[i]=random.nextFloat()*20;
            byte[] oldSnow=new byte[n],newSnow=new byte[n];var snapshot=new Snapshot<>(active,catalog.entries());
            TerralithCompat.setActive(true);
            var key=new BiomeDebugTrace.TileKey(-81985529216486895L,scale,0,0,h,w);
            var oldTile=BiomeDebugTrace.begin(key,32,snapshot);
            short[] old=AcceptedBiomeClassifier.classify(elev,climate,0,0,halo,h,w,30f/scale,oldSnow,wet,snapshot,ignored->{},oldTile);
            BiomeDebugTrace.publish(oldTile);
            var oldRecords=new HashMap<BiomeDebugTrace.Position,BiomeDebugTrace.Observation>();
            for(var p:List.of(new BiomeDebugTrace.Position(0,0),new BiomeDebugTrace.Position(4,4),new BiomeDebugTrace.Position(28,28)))
                oldRecords.put(p,BiomeDebugTrace.read(p,key).orElseThrow());
            BiomeDebugTrace.clear();
            short[] current=BiomeClassifier.classify(elev,climate,0,0,halo,h,w,30f/scale,newSnow,wet,snapshot);
            check(Arrays.equals(old,current)&&Arrays.equals(oldSnow,newSnow),"every legacy output/snow unchanged Scale"+scale);
            var publication=publication(catalog,ids(),scale,active);
            check(publication.genericActive()==active,"owning activation");
            TerralithCompat.setActive(false); // Generic is independent of this process-global flag.
            var tile=BiomeDebugTrace.begin(key,32,publication.catalog());
            int[] indexes=BiomeClassifier.classifyGeneric(elev,climate,0,0,halo,h,w,30f/scale,newSnow,wet,publication,tile);
            BiomeDebugTrace.publish(tile);
            check(Arrays.equals(oldSnow,newSnow),"every generic snow unchanged Scale"+scale);
            for(int i=0;i<n;i++){
                String chosen=publication.palette().at(indexes[i]).id();
                if(!active)check(chosen.equals(BiomeDebugTrace.biomeName(old[i])),"inactive owning fallback exact");
                if(active&&elev[i]>=0&&wet[i])check(chosen.equals(BiomeDebugTrace.biomeName(old[i])),"approved transitional river exact");
            }
            for(var item:oldRecords.entrySet()){
                var record=BiomeDebugTrace.read(item.getKey(),key).orElseThrow();compareSample(item.getValue(),record);
                check(record.unavailable().isEmpty()&&record.genericSelection()!=null,"actual complete generic capture");
                check(record.surfaceBiome().equals(publication.palette().at(record.genericSelection().paletteIndex()).id()),"actual owning index/id trace");
                check(record.genericSelection().paletteId().equals(publication.palette().id())&&record.genericSelection().spacingWorldBlocks()==1024,"captured saved config/palette");
                if(record.selectedEntry()!=null){var selected=record.entries().stream().filter(e->e.id().equals(record.selectedEntry())).findFirst().orElseThrow();
                    check(selected.eligible()&&selected.weight()>0&&selected.biome().equals(record.surfaceBiome()),"actual chosen entry eligible positive matches biome");}
            }
            BiomeDebugTrace.clear();
        }
    }
    private static void arbitrary(){
        List<String> pool=new ArrayList<>(ids());pool.add("other-provider:arbitrary_surface");pool.sort(String::compareTo);
        Entry definition=new Entry("test:any", "other-provider:arbitrary_surface",Set.of(),Set.of(Environment.land,Environment.ocean),Map.of(),Map.of(),new Selection(1,0,false));
        String holder=definition.biome();var catalog=new Snapshot<>(true,List.of(new Candidate<>(definition,holder)));
        var publication=publication(catalog,pool,2,true);int n=32*32;float[] elev=new float[n],climate=new float[4*n],halo=new float[34*34];
        Arrays.fill(climate,10f);byte[] snow=new byte[n];boolean[] wet=new boolean[n];
        int[] indexes=BiomeClassifier.classifyGeneric(elev,climate,0,0,halo,32,32,15f,snow,wet,publication,null);
        for(int index:indexes)check(publication.palette().at(index).id().equals(holder),"arbitrary target has no native short assumption");
    }
    private static void strictRouting(){
        List<String> pool=ids();String target="minecraft:desert".intern();
        var definition=new Entry("fixture:strict",target,Set.of(),Set.of(Environment.land,Environment.ocean,Environment.river),Map.of(),Map.of(),new Selection(1,0,false));
        var controller=new RegistryClassificationController<String>();
        var palette=new BiomePalette<String>(pool,id->Optional.of(id.intern()));
        check(controller.commit(new Snapshot<>(true,List.of(new Candidate<>(definition,target))),true).accepted(),"strict fixture metadata");
        var handle=controller.bind(77,1,SavedSelectionPolicy.catalogOnly(1024,pool),palette);
        int n=16*16;float[] elevation=new float[n],climate=new float[4*n],halo=new float[18*18];boolean[] wet=new boolean[n];
        Arrays.fill(climate,-20f);Arrays.fill(wet,true);
        // A desert fixture deliberately checks dispatch, not sensible product ecology:
        // even the frozen/wet path must use the explicit admitted catalog choice.
        int[] result=BiomeClassifier.classifyGeneric(elevation,climate,0,0,halo,16,16,30f,new byte[n],wet,handle.get(),null);
        for(int index:result)check(palette.at(index).id().equals(target),"frozen wet route cannot bypass catalog");
        Arrays.fill(elevation,-100f);result=BiomeClassifier.classifyGeneric(elevation,climate,0,0,halo,16,16,30f,new byte[n],wet,handle.get(),null);
        for(int index:result)check(palette.at(index).id().equals(target),"ocean route cannot bypass catalog");
        var inactiveProposal=controller.commit(new Snapshot<>(true,List.of()),false);check(!inactiveProposal.accepted(),"strict disable rejected");
        check(controller.commit(new Snapshot<>(true,List.of()),true).accepted(),"explicit empty active metadata accepted as empty");
        try{BiomeClassifier.classifyGeneric(elevation,climate,0,0,halo,16,16,30f,new byte[n],wet,handle.get(),null);throw new AssertionError("empty strict catalog silently substituted a biome");}
        catch(IllegalStateException expected){check(expected.getMessage().contains("seed=77")&&expected.getMessage().contains("no substitute biome"),"no-match failure identifies owning world and contract");}
        var zero=new Entry("fixture:zero",target,Set.of(),Set.of(Environment.ocean),Map.of(),Map.of(),new Selection(0,0,false));
        check(controller.commit(new Snapshot<>(true,List.of(new Candidate<>(zero,target))),true).accepted(),"zero-weight valid metadata");
        try{BiomeClassifier.classifyGeneric(elevation,climate,0,0,halo,16,16,30f,new byte[n],wet,handle.get(),null);throw new AssertionError("zero weight silently substituted a biome");}
        catch(IllegalStateException expected){check(expected.getMessage().contains("NO_ELIGIBLE_POSITIVE_CANDIDATE"),"zero-weight no match fails explicitly");}
        controller.release(handle);
    }
    public static void main(String[] args)throws Exception{
        strictRouting();
        check(ids().size()==116,"approved fixed pool exact116");grids(load(Path.of(args[0])));arbitrary();
        System.out.println("PASS GenericClassifierIntegrationTest "+checks+" checks; pure provided arrays, no Minecraft/ONNX");
    }
}
