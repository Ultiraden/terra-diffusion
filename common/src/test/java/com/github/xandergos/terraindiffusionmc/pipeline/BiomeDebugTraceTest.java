package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Pure production-classifier tests; only compatibility/model resolution is stubbed by the runner.
 * Runtime command non-mutation and actual normal provider publication remain separate required gates. */
public final class BiomeDebugTraceTest {
    private static int checks;
    private static final Field[] FIELDS=Arrays.stream(TerrainSample.class.getFields()).sorted(Comparator.comparing(Field::getName)).toArray(Field[]::new);
    private static int[][] samples;
    private static int offset;
    public static void capture(TerrainSample sample) {
        if(samples==null) return;
        try {
            int[] row=samples[offset++]; int i=0;
            for(Field field:FIELDS) row[i++]=field.getType()==float.class ? Float.floatToRawIntBits(field.getFloat(sample))
                : field.getType()==boolean.class ? (field.getBoolean(sample) ? 1 : 0) : field.getInt(sample);
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void check(boolean condition,String reason) { checks++; if(!condition) throw new AssertionError(reason); }
    private static void invalid(String value) {
        try { BiomeDebugTrace.parsePositions(value); throw new AssertionError("accepted invalid positions: "+value); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    private static Snapshot<String> load(Path path) throws Exception {
        List<BiomeCatalogLoader.Resource> resources=new ArrayList<>();
        try(var files=Files.walk(path)) {
            for(Path file:files.filter(p->p.toString().endsWith(".json")).sorted().toList()) {
                String relative=path.relativize(file).toString().replace('\\','/');
                resources.add(new BiomeCatalogLoader.Resource("ultira:"+relative.substring(0,relative.length()-5),"baseline",0,Files.readString(file)));
            }
        }
        var result=new BiomeCatalogLoader<String>().reload(resources,Set.of("terralith"),Optional::of);
        check(result.accepted() && result.snapshot().entries().size()==429,"baseline resolved"); return result.snapshot();
    }
    private static void grids(Snapshot<String> catalog) throws Exception {
        Random random=new Random(7007);
        for(boolean terralith:new boolean[]{true,false}) for(boolean active:new boolean[]{true,false}) for(int scale:new int[]{1,2,4,16}) {
            TerralithCompat.setActive(terralith);
            var snapshot=new Snapshot<>(active,catalog.entries());
            int h=32,w=32,n=h*w;
            float[] elev=new float[n],climate=new float[4*n],padded=new float[(h+2)*(w+2)]; boolean[] wet=new boolean[n];
            for(int i=0;i<n;i++) { elev[i]=random.nextFloat()*6000-1000; climate[i]=random.nextFloat()*65-25;
                climate[n+i]=random.nextFloat()*2500; climate[2*n+i]=random.nextFloat()*5000; climate[3*n+i]=random.nextFloat()*180; wet[i]=i%19==0; }
            for(int i=0;i<padded.length;i++) padded[i]=random.nextFloat()*30;
            byte[] oldSnow=new byte[n],newSnow=new byte[n];
            final TerralithCatalogBridge.Diagnostics[] oldDiagnostics=new TerralithCatalogBridge.Diagnostics[1],newDiagnostics=new TerralithCatalogBridge.Diagnostics[1];
            samples=new int[n][FIELDS.length]; offset=0;
            short[] old=BiomeClassifier.classify(elev,climate,0,0,padded,h,w,30f/scale,oldSnow,wet,snapshot,d->oldDiagnostics[0]=d);
            int[][] oldSamples=samples;
            samples=new int[n][FIELDS.length]; offset=0;
            var key=new BiomeDebugTrace.TileKey(7007,scale,0,0,h,w);
            var tile=BiomeDebugTrace.begin(key,32,snapshot);
            check(tile!=null,"matching configured canonical tile");
            short[] current=BiomeClassifier.classify(elev,climate,0,0,padded,h,w,30f/scale,newSnow,wet,snapshot,d->newDiagnostics[0]=d,tile);
            check(Arrays.equals(old,current),"debug on/off every output scale="+scale);
            check(Arrays.equals(oldSnow,newSnow),"debug on/off every snow scale="+scale);
            check(Arrays.deepEquals(oldSamples,samples),"debug on/off all34 fields at every column scale="+scale);
            check(Objects.equals(oldDiagnostics[0],newDiagnostics[0]),"unchanged diagnostic counters/keys");
            BiomeDebugTrace.publish(tile);
            for(int x=0;x<32;x+=4) for(int z=0;z<32;z+=4) {
                if(!BiomeDebugTrace.configured(x,z)) continue;
                var record=BiomeDebugTrace.read(new BiomeDebugTrace.Position(x,z),key).orElseThrow();
                check(record.surfaceId()==current[z*w+x] && record.snowDepth()==Byte.toUnsignedInt(newSnow[z*w+x]),"actual returned output/snow");
                check(record.unavailable().isEmpty(),"429 catalog complete within trace budget: "+record.position()+" "+record.unavailable()+" bytes="+record.bytes());
                int index=0;
                for(Field field:FIELDS) {
                    Object value=BiomeDebugTrace.Sample.class.getMethod(field.getName()).invoke(record.sample());
                    int bits=value instanceof Float f ? Float.floatToRawIntBits(f) : value instanceof Boolean b ? (b?1:0) : (Integer)value;
                    check(bits==samples[z*w+x][index++],"exact captured field "+field.getName());
                }
                check(record.context().elevation()==record.sample().elev(),"classification uses smooth elevation");
                check(record.pixelSizeM()==30f/scale,"native/upscaled pixel size retained");
                check(record.entries().size()==429,"all resolved candidates retained");
                check(record.entries().stream().map(BiomeDebugTrace.EntryResult::id).toList().equals(
                    record.entries().stream().map(BiomeDebugTrace.EntryResult::id).sorted().toList()),"ASCII candidate order");
                for(var candidate:snapshot.entries()) {
                    var result=record.entries().stream().filter(e->e.id().equals(candidate.definition().id())).findFirst().orElseThrow();
                    check(result.eligible()==BiomeCatalogFilter.eligible(candidate.definition(),record.context()),"hard eligibility equivalent");
                }
                if(!active || !terralith) check(record.selectedEntry()==null,"no hypothetical selection on bypassed catalog");
                if(record.selectedEntry()!=null) {
                    var selected=record.entries().stream().filter(e->e.id().equals(record.selectedEntry())).findFirst().orElseThrow();
                    check(selected.eligible() && selected.weight()>0 && selected.biome().equals(record.surfaceBiome()),"actual selected entry eligible positive and matches returned target");
                }
                check(record.catalogActive()==active,"captured activation");
            }
            check(BiomeDebugTrace.retainedBytes()<=BiomeDebugTrace.MAX_TOTAL_BYTES,"total memory bound");
            BiomeDebugTrace.clear();
        }
        samples=null; TerralithCompat.setActive(true);
    }
    private static Entry definition(String id,String target,Map<BiomeCatalog.Field,Range> ranges,Map<Predicate,Boolean> predicates,double weight) {
        return new Entry(id,target,Set.of(),Set.of(Environment.land),ranges,predicates,new Selection(weight,17,true));
    }
    private static void reasons() {
        var entry=definition("a:test","minecraft:plains",Map.of(BiomeCatalog.Field.temperature_c,new Range(1d,2d,false,false),
            BiomeCatalog.Field.physical_bathymetry_m,new Range(0d,null,true,true)),Map.of(Predicate.has_snow,true,Predicate.coast,false),0);
        var context=new BiomeCatalogFilter.Values(Environment.river,Map.of(BiomeCatalog.Field.temperature_c,1d),Map.of(Predicate.has_snow,false));
        var result=BiomeDebugTrace.explain(entry,context);
        check(result.rejections().equals(List.of("environment:river","temperature_c:excluded-min","physical_bathymetry_m:unavailable",
            "has_snow:requires-true","coast:unavailable")),"all reasons deterministic semantic order");
        check(result.weight()==0 && result.priority()==17 && result.fallback(),"selection metadata independent");
        check(BiomeDebugTrace.explain(entry,new BiomeCatalogFilter.Values(Environment.land,Map.of(BiomeCatalog.Field.temperature_c,Double.NaN),Map.of()))
            .rejections().contains("temperature_c:nonfinite"),"nonfinite distinct from unavailable");
        var unconstrained=definition("a:eligible-zero","minecraft:plains",Map.of(),Map.of(),0);
        check(BiomeDebugTrace.explain(unconstrained,context).rejections().equals(List.of("environment:river")),"unconstrained unavailable inputs ignored");
        var good=new BiomeCatalogFilter.Values(Environment.land,Map.of(),Map.of());
        check(BiomeDebugTrace.explain(unconstrained,good).eligible(),"zero weight hard eligible");
    }
    private static void lifetime(Snapshot<String> baseline) {
        var key=new BiomeDebugTrace.TileKey(71,1,0,0,32,32); var p=new BiomeDebugTrace.Position(0,0);
        var old=BiomeDebugTrace.begin(key,32,baseline);
        check(BiomeDebugTrace.read(p,key).isEmpty(),"not published at enqueue");
        float[] padded=new float[9]; Arrays.fill(padded,100f);
        BiomeClassifier.classify(new float[]{100},new float[]{18,100,1000,20},0,0,padded,1,1,30,new byte[1],null,baseline,d->{},old);
        check(BiomeDebugTrace.read(p,key).isEmpty(),"not published at classification");
        BiomeDebugTrace.publish(old); var first=BiomeDebugTrace.read(p,key).orElseThrow();
        var empty=new Snapshot<String>(true,List.of());
        check(BiomeDebugTrace.read(p,key).orElseThrow()==first,"reload alone retains old observation");
        var fresh=BiomeDebugTrace.begin(key,32,empty);
        BiomeClassifier.classify(new float[]{100},new float[]{18,100,1000,20},0,0,padded,1,1,30,new byte[1],null,empty,d->{},fresh);
        check(BiomeDebugTrace.read(p,key).orElseThrow()==first,"pending later capture cannot replace completed old trace");
        BiomeDebugTrace.publish(fresh); var next=BiomeDebugTrace.read(p,key).orElseThrow();
        check(next.catalogEntries()==0 && !next.catalogId().equals(first.catalogId()),"later successful task uses empty captured catalog");
        check(next.fallback().contains("POLICY_NONE"),"valid empty deterministic fallback");
        int bytes=BiomeDebugTrace.retainedBytes(),count=BiomeDebugTrace.retainedRecords();
        for(int i=0;i<100;i++) check(BiomeDebugTrace.read(p,key).orElseThrow()==next,"immutable repeat identity");
        check(BiomeDebugTrace.retainedBytes()==bytes && BiomeDebugTrace.retainedRecords()==count,"read no retention mutation");
        check(BiomeDebugTrace.read(p,new BiomeDebugTrace.TileKey(72,1,0,0,32,32)).isEmpty(),"seed provenance enforced");
        check(BiomeDebugTrace.begin(new BiomeDebugTrace.TileKey(71,1,0,0,32,33),32,baseline)==null,"overlapping noncanonical key ignored");
        BiomeDebugTrace.clear(); BiomeDebugTrace.publish(old);
        check(BiomeDebugTrace.read(p,key).isEmpty(),"old task cannot publish across lifecycle epoch");
        var missing=BiomeDebugTrace.begin(key,32,baseline);
        BiomeClassifier.classify(new float[]{100},null,0,0,padded,1,1,30,new byte[1],null,baseline,d->{},missing);
        BiomeDebugTrace.publish(missing);
        check(BiomeDebugTrace.read(p,key).orElseThrow().unavailable().equals("precise climate unavailable"),"missing climate explicit");
        BiomeDebugTrace.clear();
        List<Candidate<String>> enormous=new ArrayList<>();
        for(int i=0;i<5000;i++) enormous.add(new Candidate<>(definition("a:entry"+i,"minecraft:plains",Map.of(),Map.of(),1),"minecraft:plains"));
        var huge=new Snapshot<>(true,enormous); var limited=BiomeDebugTrace.begin(key,32,huge);
        BiomeClassifier.classify(new float[]{100},new float[]{18,100,1000,20},0,0,padded,1,1,30,new byte[1],null,huge,d->{},limited);
        BiomeDebugTrace.publish(limited);
        check(BiomeDebugTrace.read(p,key).orElseThrow().unavailable().equals("record trace budget exceeded"),"large valid catalog explicit unavailable");
        check(BiomeDebugTrace.read(p,key).orElseThrow().entries().isEmpty(),"no silently incomplete eligibility list");
        BiomeDebugTrace.clear();
        var mapping=baseline.entries().stream().filter(c->c.definition().biome().equals("terralith:deep_warm_ocean")).findFirst().orElseThrow().definition();
        var zero=new Entry(mapping.id(),mapping.biome(),mapping.requiredMods(),mapping.environments(),mapping.ranges(),mapping.predicates(),new Selection(0,10,true));
        // Ocean rule1 can be admitted but its selected exact mapping has zero weight.
        var zeroCatalog=new Snapshot<>(true,List.of(new Candidate<>(zero,mapping.biome())));
        var zeroTile=BiomeDebugTrace.begin(key,32,zeroCatalog);
        Arrays.fill(padded,-700f);
        BiomeClassifier.classify(new float[]{-700},new float[]{22,100,1000,20},0,0,padded,1,1,30,new byte[1],null,zeroCatalog,d->{},zeroTile);
        BiomeDebugTrace.publish(zeroTile); var veto=BiomeDebugTrace.read(p,key).orElseThrow();
        check(veto.selectedEntry()==null && veto.fallback().contains("SELECTED_MAPPING_UNAVAILABLE"),"selected unavailable mapping stops and falls back");
        check(veto.gates().stream().anyMatch(g->g.endsWith("selected-mapping-unavailable=zero-weight")),"actual zero-weight rejection event");
        BiomeDebugTrace.clear();
        var negativeKey=new BiomeDebugTrace.TileKey(71,1,-32,-32,0,0);
        var negativeTile=BiomeDebugTrace.begin(negativeKey,32,baseline);
        BiomeClassifier.classify(new float[]{-700},new float[]{22,100,1000,20},-4,-4,padded,1,1,30,new byte[1],null,baseline,d->{},negativeTile);
        BiomeDebugTrace.publish(negativeTile);
        check(BiomeDebugTrace.read(new BiomeDebugTrace.Position(-4,-4),negativeKey).orElseThrow().sample().worldX()==-4,"negative quart-origin exact source coordinate");
        BiomeDebugTrace.clear();
        var oversized=BiomeDebugTrace.begin(key,32,baseline);
        var selection=new BiomeDebugTrace.Selection(); selection.chosen(1,"a:"+"a".repeat(1024*1024));
        TerrainSample sample=new TerrainSample(); sample.worldX=0; sample.worldZ=0;
        oversized.capture(sample,false,30,"legacy-bridge","fixture",(short)1,1,selection,"none");
        BiomeDebugTrace.publish(oversized);
        check(BiomeDebugTrace.read(p,key).orElseThrow().unavailable().equals("selection trace budget exceeded"),"oversized chosen ID guarded before concatenation");
        BiomeDebugTrace.clear();
        var hugeId=definition("a:"+"a".repeat(1024*1024),"minecraft:plains",Map.of(),Map.of(),1);
        var hugeIdCatalog=new Snapshot<>(true,List.of(new Candidate<>(hugeId,hugeId.biome())));
        var hugeIdTile=BiomeDebugTrace.begin(key,32,hugeIdCatalog);
        BiomeClassifier.classify(new float[]{100},new float[]{18,100,1000,20},0,0,padded,1,1,30,new byte[1],null,hugeIdCatalog,d->{},hugeIdTile);
        BiomeDebugTrace.publish(hugeIdTile);
        check(BiomeDebugTrace.read(p,key).orElseThrow().unavailable().equals("record trace budget exceeded"),"oversized entry ID guarded before explanation, fixed digest scratch");
        BiomeDebugTrace.clear();
    }
    public static void main(String[] args) throws Exception {
        check(BiomeDebugTrace.enabled(),"explicit startup opt-in");
        check(BiomeDebugTrace.parsePositions("").isEmpty(),"disabled default");
        check(BiomeDebugTrace.parsePositions("-4,-4;0,0").size()==2,"negative quart alignment");
        for(String invalid:List.of("1,0","0,0;0,0","0,0;"," 0,0","0,0;4","2147483648,0")) invalid(invalid);
        invalid(String.join(";",Collections.nCopies(65,"0,0")));
        var baseline=load(Path.of(args[0]));
        if(args.length>1 && args[1].equals("budget")) { budget(baseline); System.out.println("PASS: "+checks+" 64-position total/tile memory-bound assertions"); return; }
        reasons(); grids(baseline); lifetime(baseline);
        System.out.println("PASS: "+checks+" debug capture assertions; 16 native/upsampled legacy/bridge grids, outputs/snow/all34 fields and diagnostics identical");
    }
    private static void budget(Snapshot<String> baseline) {
        int n=32*32; float[] elev=new float[n],climate=new float[n*4],padded=new float[34*34];
        Arrays.fill(elev,100); Arrays.fill(padded,100); Arrays.fill(climate,0,n,-20); Arrays.fill(climate,n,2*n,2000);
        Arrays.fill(climate,2*n,3*n,100); Arrays.fill(climate,3*n,4*n,180);
        var key=new BiomeDebugTrace.TileKey(77,1,0,0,32,32);
        var tile=BiomeDebugTrace.begin(key,32,baseline);
        BiomeClassifier.classify(elev,climate,0,0,padded,32,32,30,new byte[n],null,baseline,d->{},tile);
        BiomeDebugTrace.publish(tile); int available=0,unavailable=0;
        for(int x=0;x<32;x+=4) for(int z=0;z<32;z+=4) {
            var record=BiomeDebugTrace.read(new BiomeDebugTrace.Position(x,z),key).orElseThrow();
            check(record.bytes()<=BiomeDebugTrace.MAX_RECORD_BYTES,"per-record memory bound");
            if(record.unavailable().isEmpty()) { available++; check(record.entries().size()==429,"complete budget-admitted record"); }
            else { unavailable++; check(record.entries().isEmpty(),"budget explicitly unavailable; no partial list"); }
        }
        check(available>0 && unavailable>0,"both full records and explicit total budget status exercised");
        check(BiomeDebugTrace.retainedRecords()==64,"strict64-position configured maximum");
        check(BiomeDebugTrace.retainedBytes()<=BiomeDebugTrace.MAX_TOTAL_BYTES,"total retained bound");
    }
}
