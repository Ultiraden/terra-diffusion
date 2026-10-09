package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.*;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Pinned original derivation is compiled separately by the pack verifier.
 * Its sole instrumentation captures the derived sample, never changes output. */
public final class CatalogBridgeTest {
    public static TerrainSample sample;
    private static final Field[] SAMPLE_FIELDS=Arrays.stream(TerrainSample.class.getFields()).sorted(Comparator.comparing(Field::getName)).toArray(Field[]::new);
    private static int[] originalSamples,currentSamples;
    private static int originalOffset,currentOffset;
    /** Test-only observer inserted into temporary original/current copies. */
    public static void capture(TerrainSample s,boolean current) throws Exception {
        if(!current) sample=s;
        int[] output=current ? currentSamples : originalSamples;
        if(output==null) return;
        int offset=current ? currentOffset : originalOffset;
        for(Field f:SAMPLE_FIELDS) {
            if(f.getType()==float.class) output[offset++]=Float.floatToRawIntBits(f.getFloat(s));
            else if(f.getType()==int.class) output[offset++]=f.getInt(s);
            else if(f.getType()==boolean.class) output[offset++]=f.getBoolean(s) ? 1 : 0;
            else throw new AssertionError("unobserved derived sample field "+f);
        }
        if(current) currentOffset=offset; else originalOffset=offset;
    }
    private static int checks;
    private static final Set<Short> seen = new TreeSet<>();
    private static Method oracle, vanilla;
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    private static Snapshot<String> load(Path directory) throws Exception {
        List<BiomeCatalogLoader.Resource> resources = new ArrayList<>();
        try (var files = Files.walk(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                String relative = directory.relativize(file).toString().replace('\\','/');
                resources.add(new BiomeCatalogLoader.Resource("ultira:"+relative.substring(0,relative.length()-5),"baseline",0,Files.readString(file)));
            }
        }
        var result = new BiomeCatalogLoader<String>().reload(resources,Set.of("terralith"),Optional::of);
        check(result.accepted() && result.snapshot().entries().size()==429,"complete accepted baseline");
        return result.snapshot();
    }
    private static short oracle(float elevation,float temperature,float season,float precipitation,float cv,float slope,int x,int z,boolean wet) throws Exception {
        float[] padded = new float[9];
        for(int r=0;r<3;r++) for(int c=0;c<3;c++) padded[r*3+c]=elevation+(c-1)*slope*30f;
        return ((short[])oracle.invoke(null,new float[]{elevation},new float[]{temperature,season,precipitation,cv},
            z,x,padded,1,1,30f,null,new boolean[]{wet}))[0];
    }
    private static short local(TerrainSample s, boolean wet, TerralithCatalogBridge bridge) {
        if (!s.isOcean && wet) {
            if (s.temp <= -3f) return 11;
            short warm = bridge.warmRiver(s);
            return warm == 0 ? (short)7 : warm;
        }
        return bridge.pick(s);
    }
    private static void compareSample(TerrainSample s, TerralithCatalogBridge bridge, String label) {
        short original = TerralithClassifier.pick(s), actual = bridge.pick(s);
        check(actual==original,label+": local "+actual+" != "+original+" at ("+s.worldX+","+s.worldZ+") elev="+s.elev+" temp="+s.temp+" slope="+s.slope);
        seen.add(original);
    }
    private static void randomDerived(Snapshot<String> snapshot) throws Exception {
        Random rng = new Random(3005);
        TerralithCatalogBridge bridge = new TerralithCatalogBridge(snapshot);
        check(bridge.bindingDiagnostics().isEmpty(),"all exact baseline bindings");
        float[] heights={-600f,-120f,0f,8f,200f,700f,900f,1500f,2500f,3200f};
        float[] temperatures={-7f,-5f,-3f,5f,12f,18f,20f,26f,28f};
        for (int i=0;i<120000;i++) {
            float elevation=-1500f+rng.nextFloat()*7000f, temperature=-25f+rng.nextFloat()*70f;
            float season=rng.nextFloat()*2500f, precipitation=(float)Math.exp(rng.nextDouble()*9.3);
            float cv=rng.nextFloat()*180f, slope=rng.nextFloat()*1.4f;
            if(i<3000) { float cut=heights[i/300]; elevation=i%3==0?Math.nextDown(cut):i%3==1?cut:Math.nextUp(cut); }
            if(i>=3000 && i<5700) temperature=temperatures[(i-3000)/300];
            boolean wet=i%19==0;
            // Same coordinates/order as the accepted #3 120,000-sample fixture.
            int z=rng.nextInt(2000000)-1000000, x=rng.nextInt(2000000)-1000000;
            short original=oracle(elevation,temperature,season,precipitation,cv,slope,x,z,wet);
            TerrainSample s=sample;
            short chosen=local(s,wet,bridge);
            short actual=chosen==0 ? (Short)vanilla.invoke(null,s) : chosen;
            check(actual==original,"derived final #"+i+": "+actual+" != "+original);
            if(chosen!=0) seen.add(chosen);
        }
        check(seen.containsAll(TerralithBiomeIds.paths().keySet()),"every one of 77 Terralith returns covered: "+seen);
        check(seen.contains((short)29) && seen.contains((short)44),"direct meadow/ocean covered");
        check(bridge.unavailableMappingCount()==0,"no baseline mapping veto");
        System.out.println("PASS: 120000 final-output comparisons, all 79 inventoried returns");
    }
    private static TerrainSample fixture(float elevation,float temperature,Random random) {
        TerrainSample s=new TerrainSample(); s.elev=elevation; s.altM=Math.max(0f,elevation); s.temp=temperature;
        s.worldX=random.nextInt(2000000)-1000000; s.worldZ=random.nextInt(2000000)-1000000;
        s.precip=random.nextFloat()*5000f; s.aridity=random.nextFloat(); s.treeMoisture=random.nextFloat()*1.6f;
        s.slope=random.nextFloat(); s.tStd=random.nextFloat()*20f; s.hasSnow=random.nextBoolean();
        int tree=random.nextInt(5); s.treesNone=tree==0; s.treesSparse=tree==1; s.treesForest=tree==2; s.treesDense=tree==3; s.treesRainforest=tree==4;
        s.slopeBare=random.nextInt(5)==0; s.slopeMedium=!s.slopeBare && random.nextBoolean();
        return bands(s);
    }
    private static TerrainSample bands(TerrainSample s) {
        s.altM=Math.max(0f,s.elev); s.isOcean=s.elev<0f; s.frozen=s.temp < -7f;
        s.cold=s.temp>=-7f && s.temp<5f; s.cool=s.temp>=5f && s.temp<12f;
        s.temperate=s.temp>=12f && s.temp<20f; s.warm=s.temp>=20f && s.temp<26f; s.hot=s.temp>=26f;
        return s;
    }
    private static void boundaries(Snapshot<String> snapshot) {
        Map<BiomeCatalog.Field,Set<Float>> thresholds=new EnumMap<>(BiomeCatalog.Field.class);
        for(var c:snapshot.entries()) for(var r:c.definition().ranges().entrySet()) {
            if(r.getValue().min()!=null) thresholds.computeIfAbsent(r.getKey(),k->new TreeSet<>()).add(r.getValue().min().floatValue());
            if(r.getValue().max()!=null) thresholds.computeIfAbsent(r.getKey(),k->new TreeSet<>()).add(r.getValue().max().floatValue());
        }
        Random random=new Random(5003); TerralithCatalogBridge bridge=new TerralithCatalogBridge(snapshot); int count=0;
        for(var field:thresholds.entrySet()) for(float endpoint:field.getValue()) for(float value:new float[]{Math.nextDown(endpoint),endpoint,Math.nextUp(endpoint)}) for(int i=0;i<192;i++) {
            TerrainSample s=fixture(-1000f+random.nextFloat()*6000f,-20f+random.nextFloat()*60f,random);
            switch(field.getKey()) {
                case temperature_c -> s.temp=value;
                case precipitation_mm -> s.precip=value;
                case classification_elevation_m -> s.elev=value;
                case classification_slope -> s.slope=value;
                case aridity -> s.aridity=value;
                case tree_moisture -> s.treeMoisture=value;
                case summer_max_c -> { s.temp=value; s.tStd=0f; }
                default -> throw new AssertionError(field.getKey());
            }
            bands(s); compareSample(s,bridge,"boundary "+field.getKey()+"="+value); count++;
        }
        System.out.println("PASS: "+count+" source-vs-bridge contextual boundary comparisons");
    }
    private static void actualGrid(Snapshot<String> snapshot) throws Exception {
        Random rng=new Random(5004);
        for(int tile=0;tile<17;tile++) {
            int height=tile==16 ? 1024 : 64,width=height,n=height*width;
            float[] elevation=new float[n],climate=new float[4*n],padded=new float[(height+2)*(width+2)]; boolean[] wet=new boolean[n];
            for(int i=0;i<n;i++) {
                elevation[i]=-1000f+rng.nextFloat()*6000f; climate[i]=-20f+rng.nextFloat()*60f;
                climate[n+i]=rng.nextFloat()*2500f; climate[2*n+i]=rng.nextFloat()*5000f; climate[3*n+i]=rng.nextFloat()*180f;
                wet[i]=i%19==0;
            }
            for(int i=0;i<padded.length;i++) padded[i]=rng.nextFloat()*20f;
            byte[] oldSnow=new byte[n],newSnow=new byte[n];
            originalSamples=new int[n*SAMPLE_FIELDS.length]; currentSamples=new int[originalSamples.length]; originalOffset=0; currentOffset=0;
            short[] old=(short[])oracle.invoke(null,elevation,climate,tile*64,-tile*64,padded,height,width,30f,oldSnow,wet);
            final TerralithCatalogBridge.Diagnostics[] diagnostics=new TerralithCatalogBridge.Diagnostics[1];
            short[] current=BiomeClassifier.classify(elevation,climate,tile*64,-tile*64,padded,height,width,30f,newSnow,wet,snapshot,d->diagnostics[0]=d);
            check(Arrays.equals(old,current),"actual grid final output tile "+tile);
            check(Arrays.equals(oldSnow,newSnow),"unchanged actual snow tile "+tile);
            check(originalOffset==originalSamples.length && currentOffset==currentSamples.length,"every derived field/flag observed tile "+tile);
            check(Arrays.equals(originalSamples,currentSamples),"exact derived sample float bits/flags tile "+tile);
            check(diagnostics[0]!=null && diagnostics[0].selectedMappingUnavailable()==0 && diagnostics[0].bindings().isEmpty(),"no baseline mapping mismatch tile "+tile);
            originalSamples=null; currentSamples=null;
        }
        System.out.println("PASS: 1114112 actual production-classifier outputs (including 1024-square tile), snow bytes and all "+SAMPLE_FIELDS.length+" derived fields/flags");
    }
    private static Snapshot<String> alter(Snapshot<String> snapshot,int rule,Function<Entry,Entry> change) {
        Set<String> ids=Set.of(TerralithCatalogBindings.RESOURCES[rule]);
        List<Candidate<String>> entries=new ArrayList<>();
        for(var candidate:snapshot.entries()) {
            Entry entry=ids.contains(candidate.definition().id()) ? change.apply(candidate.definition()) : candidate.definition();
            if(entry!=null) entries.add(new Candidate<>(entry,entry.biome()));
        }
        return new Snapshot<>(true,entries);
    }
    private static TerrainSample controlled(float elevation,float temperature,int tree,float slope) {
        TerrainSample s=fixture(elevation,temperature,new Random(5005)); s.slope=slope; s.slopeMedium=false; s.slopeBare=false; s.hasSnow=false;
        s.aridity=1f; s.treeMoisture=1f; s.precip=1000f; s.tStd=0f;
        s.treesNone=tree==0; s.treesSparse=tree==1; s.treesForest=tree==2; s.treesDense=tree==3; s.treesRainforest=tree==4;
        return s;
    }
    private static TerrainSample find(TerrainSample s,java.util.function.Predicate<TerrainSample> condition) {
        Random coordinates=new Random(5006);
        for(int i=0;i<100000;i++) {
            s.worldX=coordinates.nextInt(2000000)-1000000; s.worldZ=coordinates.nextInt(2000000)-1000000;
            if(condition.test(s)) return s;
        }
        throw new AssertionError("no deterministic source-policy fixture found");
    }
    private static void selectedUnavailable(Snapshot<String> snapshot,int rule,TerrainSample s,String label) {
        TerralithCatalogBridge bridge=new TerralithCatalogBridge(snapshot);
        check(bridge.pick(s)==0,label+" does not borrow another variant/route");
        check(bridge.lastNoMatch()==TerralithCatalogBridge.NoMatch.SELECTED_MAPPING_UNAVAILABLE,label+" distinct selected-mapping diagnostic");
        check(bridge.unavailableMappingCount()==1 && bridge.noMatchCount()==1,label+" counted once");
        check(bridge.diagnostics().selectedFailures().size()==1 && bridge.diagnostics().selectedFailures().values().iterator().next()==1L,label+" exact-group failure diagnostic");
    }
    private static void overridesAndNone(Snapshot<String> snapshot) throws Exception {
        TerrainSample coldPlateau=find(controlled(1000f,8f,2,0.1f),s->TerralithClassifier.pick(s)==TerralithBiomeIds.SHIELD);
        Snapshot<String> removedPlateau=alter(snapshot,31,e->null);
        check(filterHas(removedPlateau,coldPlateau,32),"broad plateau forest mapping remains admitted");
        selectedUnavailable(removedPlateau,31,coldPlateau,"all cold plateau Shield alternatives removed");
        TerrainSample coldHighland=find(controlled(1000f,8f,2,0.3f),s->TerralithClassifier.pick(s)==TerralithBiomeIds.SHIELD);
        Snapshot<String> removedHighland=alter(snapshot,68,e->null);
        check(filterHas(removedHighland,coldHighland,69),"broad highland forest mapping remains admitted");
        selectedUnavailable(removedHighland,68,coldHighland,"all cold highland Shield alternatives removed");
        TerrainSample sparse=controlled(1000f,22f,1,0.3f);
        check(TerralithClassifier.pick(sparse)==TerralithBiomeIds.TEMPERATE_HIGHLANDS,"source sparse fixture");
        Snapshot<String> removedSparse=alter(snapshot,71,e->null);
        check(filterHas(removedSparse,sparse,69),"broad forest mapping overlaps removed sparse choice");
        selectedUnavailable(removedSparse,71,sparse,"all sparse alternatives removed");
        Snapshot<String> zero=alter(snapshot,68,e->new Entry(e.id(),e.biome(),e.requiredMods(),e.environments(),e.ranges(),e.predicates(),new Selection(0,999,true)));
        check(filterHas(zero,coldHighland,68),"zero weight remains generically eligible");
        selectedUnavailable(zero,68,coldHighland,"zero-weight selected mapping");
        Snapshot<String> mismatch=alter(snapshot,68,e->new Entry(e.id(),"minecraft:forest",e.requiredMods(),e.environments(),e.ranges(),e.predicates(),e.selection()));
        check(filterHas(mismatch,coldHighland,68),"registered replacement remains a generic candidate");
        selectedUnavailable(mismatch,68,coldHighland,"same resource with different registered target");
        check(new TerralithCatalogBridge(mismatch).bindingDiagnostics().stream().anyMatch(d->d.contains("target mismatch")),"target mismatch diagnostic");
        Snapshot<String> narrowed=alter(snapshot,68,e->{ Map<BiomeCatalog.Field,Range> ranges=new EnumMap<>(e.ranges()); ranges.put(BiomeCatalog.Field.physical_bathymetry_m,new Range(0d,null,true,true)); return new Entry(e.id(),e.biome(),e.requiredMods(),e.environments(),ranges,e.predicates(),e.selection()); });
        check(!filterHas(narrowed,coldHighland,68),"unavailable future input narrows selected mapping");
        selectedUnavailable(narrowed,68,coldHighland,"selected mapping narrowed to unavailable input");
        TerrainSample flowers=find(controlled(500f,15f,2,0.3f),s->TerralithClassifier.pick(s)==TerralithBiomeIds.LAVENDER_FOREST);
        selectedUnavailable(alter(snapshot,129,e->null),129,flowers,"selected showpiece variant removed");
        TerrainSample cliff=controlled(3301f,-10f,0,1.1f); cliff.slopeBare=true;
        find(cliff,s->TerralithClassifier.pick(s)==0);
        TerralithCatalogBridge bridge=new TerralithCatalogBridge(snapshot);
        check(bridge.pick(cliff)==0 && bridge.lastNoMatch()==TerralithCatalogBridge.NoMatch.POLICY_NONE,"terminal frozen cliff NONE");
        TerrainSample alpine=controlled(3000f,0f,0,0.3f); alpine.hasSnow=true;
        find(alpine,s->TerralithClassifier.pick(s)==0);
        check(bridge.pick(alpine)==0 && bridge.lastNoMatch()==TerralithCatalogBridge.NoMatch.POLICY_NONE,"terminal snowy nonvolcanic alpine NONE");
        TerrainSample montane=controlled(2000f,-6f,0,0.3f); montane.hasSnow=true; montane.precip=700f;
        find(montane,s->TerralithClassifier.pick(s)==0);
        check(filterHas(snapshot,montane,44),"Yellowstone admission exists before failed special noise");
        check(bridge.pick(montane)==0 && bridge.lastNoMatch()==TerralithCatalogBridge.NoMatch.POLICY_NONE,"montane special gate cannot defeat snowy NONE");
        TerrainSample volcanic=controlled(1000f,22f,0,0.1f); volcanic.aridity=0.3f;
        find(volcanic,s->TerralithClassifier.pick(s)==TerralithBiomeIds.ASHEN_SAVANNA);
        check(bridge.pick(volcanic)==TerralithBiomeIds.ASHEN_SAVANNA,"volcanic plateau policy NONE continues to highland");
        TerrainSample ordinary=find(controlled(500f,15f,2,0.3f),s->{ short selected=TerralithClassifier.pick(s); return selected==TerralithBiomeIds.SHIELD_CLEARING || selected==TerralithBiomeIds.YOSEMITE_LOWLANDS; });
        check(filterHas(snapshot,ordinary,129),"showpiece admission exists before failed noise");
        check(bridge.pick(ordinary)==TerralithClassifier.pick(ordinary),"failed showpiece policy noise continues caller");
        TerrainSample cloud=controlled(2000f,15f,2,0.3f); cloud.precip=2000f;
        check(TerralithClassifier.pick(cloud)==TerralithBiomeIds.CLOUD_FOREST,"source data-only cloud family fixture");
        TerralithCatalogBridge removedCloud=new TerralithCatalogBridge(alter(snapshot,46,e->null));
        check(removedCloud.pick(cloud)==TerralithBiomeIds.HAZE_MOUNTAIN,"whole data-only family removal follows remaining catalog gates");
        check(removedCloud.unavailableMappingCount()==0 && !removedCloud.bindingDiagnostics().isEmpty(),"family absence diagnosed separately from selected variant mismatch");
        check(new TerralithCatalogBridge(new Snapshot<>(true,List.of())).pick(cloud)==0,"active empty no-match");
        TerrainSample warm=controlled(100f,29f,2,0.3f);
        TerralithCatalogBridge zeroWarm=new TerralithCatalogBridge(alter(snapshot,4,e->new Entry(e.id(),e.biome(),e.requiredMods(),e.environments(),e.ranges(),e.predicates(),new Selection(0,0,false))));
        check(zeroWarm.warmRiver(warm)==0 && zeroWarm.lastNoMatch()==TerralithCatalogBridge.NoMatch.SELECTED_MAPPING_UNAVAILABLE,"warm river selected failure observable");
        check(zeroWarm.noMatchCount()==1 && zeroWarm.warmRiverNoMatchCount()==1 && zeroWarm.unavailableMappingCount()==1,"warm river fallback counters");
        System.out.println("PASS: explicit override, exact-group and policy NONE fixtures");
    }
    private static boolean filterHas(Snapshot<String> snapshot,TerrainSample s,int rule) {
        Set<String> ids=Set.of(TerralithCatalogBindings.RESOURCES[rule]);
        return BiomeCatalogFilter.filter(snapshot,SampleCatalogContext.from(s,false)).stream().anyMatch(c->ids.contains(c.definition().id()));
    }
    private static void laterNonfiniteInputs(Snapshot<String> snapshot) {
        TerrainSample s=controlled(1000f,15f,2,0.3f);
        TerralithCatalogBridge bridge=new TerralithCatalogBridge(snapshot);
        bridge.pick(s);
        check(bridge.diagnostics().unavailableInputs().isEmpty(),"finite first column has no unavailable baseline input");
        s.treeMoisture=Float.NaN;
        bridge.pick(s);
        Set<String> expected=new TreeSet<>();
        for(var candidate:snapshot.entries()) for(String input:BiomeCatalogFilter.unavailable(candidate.definition(),SampleCatalogContext.from(s,false)))
            expected.add(candidate.definition().id()+": unavailable/nonfinite "+input);
        check(!expected.isEmpty() && bridge.diagnostics().unavailableInputs().equals(List.copyOf(expected)),"nonfinite moisture after first column reports every constrained entry");
        s.treeMoisture=1f; s.temp=Float.POSITIVE_INFINITY;
        bridge.pick(s);
        for(var candidate:snapshot.entries()) for(String input:BiomeCatalogFilter.unavailable(candidate.definition(),SampleCatalogContext.from(s,false)))
            expected.add(candidate.definition().id()+": unavailable/nonfinite "+input);
        check(bridge.diagnostics().unavailableInputs().equals(List.copyOf(expected)),"later distinct nonfinite fields extend sorted diagnostics");
        s.temp=15f; s.treeMoisture=Float.NaN;
        bridge.pick(s);
        check(bridge.diagnostics().unavailableInputs().equals(List.copyOf(expected)),"repeated nonfinite input does not duplicate or lose diagnostics");
        System.out.println("PASS: later-column nonfinite input diagnostics");
    }
    private static void enabledEmptyClassification() throws Exception {
        float[] elevation={500f}, climate={15f,0f,1000f,0f}, padded=new float[9]; Arrays.fill(padded,500f);
        short legacy=oracle(500f,15f,0f,1000f,0f,0f,0,0,false);
        short expectedVanilla=(Short)vanilla.invoke(null,sample);
        check(legacy!=expectedVanilla,"empty opt-in fixture distinguishes legacy choice from vanilla fallback");
        CatalogClassificationState<String> state=new CatalogClassificationState<>();
        var marker=new BiomeCatalogLoader.Resource(CatalogClassificationState.ACTIVATION_RESOURCE,"fixture",0,"{\"schema_version\":1,\"enabled\":true}");
        state.activate(state.propose(List.of(),Optional.of(marker),Set.of(),Optional::of));
        check(BiomeClassifier.classify(elevation,climate,0,0,padded,1,1,30f,null,null,state.classificationSnapshot())[0]==expectedVanilla,
            "enabled empty production classifier uses exact pinned vanilla fallback");
        state.activate(state.propose(List.of(),Optional.empty(),Set.of(),Optional::of));
        check(BiomeClassifier.classify(elevation,climate,0,0,padded,1,1,30f,null,null,state.classificationSnapshot())[0]==legacy,
            "missing marker production classifier preserves exact legacy output");
        System.out.println("PASS: enabled empty versus legacy production classification");
    }
    private static void adapterContract() {
        TerrainSample s=controlled(100f,15f,2,0.3f); s.tStd=15f/1.414f; s.pCV=-2f; s.effTreeMoisture=0.45f; s.growingSeason=200f;
        SampleCatalogContext context=SampleCatalogContext.from(s,false);
        Map<BiomeCatalog.Field,Double> expected=Map.ofEntries(
            Map.entry(BiomeCatalog.Field.temperature_c,(double)s.temp),Map.entry(BiomeCatalog.Field.precipitation_mm,(double)s.precip),
            Map.entry(BiomeCatalog.Field.temperature_seasonality,(double)s.tStd),Map.entry(BiomeCatalog.Field.precipitation_cv,(double)s.pCV),
            Map.entry(BiomeCatalog.Field.classification_elevation_m,(double)s.elev),Map.entry(BiomeCatalog.Field.classification_slope,(double)s.slope),
            Map.entry(BiomeCatalog.Field.aridity,(double)s.aridity),Map.entry(BiomeCatalog.Field.tree_moisture,(double)s.treeMoisture),
            Map.entry(BiomeCatalog.Field.effective_tree_moisture,(double)s.effTreeMoisture),Map.entry(BiomeCatalog.Field.growing_season_days,(double)s.growingSeason),
            Map.entry(BiomeCatalog.Field.summer_max_c,(double)(s.temp+1.414f*s.tStd)));
        for(var field:expected.entrySet()) check(context.numeric(field.getKey()).orElseThrow()==field.getValue(),"exact adapter field "+field.getKey());
        check(context.number(BiomeCatalog.Field.summer_max_c)==30d,"f32 staged summer adapter");
        boolean[] flags={s.hasSnow,s.treesNone,s.treesSparse,s.treesForest,s.treesDense,s.treesRainforest,s.barren,s.tooArid,s.tooCold,s.slopeMedium,s.slopeBare};
        for(int i=0;i<flags.length;i++) {
            BiomeCatalog.Predicate predicate=BiomeCatalog.Predicate.values()[i];
            check(context.state(predicate).orElseThrow()==flags[i] && context.stateCode(predicate)==(flags[i]?1:0),"exact post-override adapter predicate "+predicate);
        }
        for(BiomeCatalog.Field field:List.of(BiomeCatalog.Field.physical_bathymetry_m,BiomeCatalog.Field.water_depth_blocks)) check(context.numeric(field).isEmpty(),"future unavailable "+field);
        for(BiomeCatalog.Predicate p:List.of(BiomeCatalog.Predicate.coast,BiomeCatalog.Predicate.submerged)) check(context.state(p).isEmpty(),"future state unavailable "+p);
        check(context.environment()==Environment.land && SampleCatalogContext.from(s,true).environment()==Environment.river,"land/wet environment");
        s.elev=-1f; s.isOcean=true; check(SampleCatalogContext.from(s,true).environment()==Environment.ocean,"ocean precedence over wet");
        float captured=context.elevation(); s.elev=99f; check(context.elevation()==captured,"immutable sample capture");
    }
    public static void main(String[] args) throws Exception {
        Class<?> original=Class.forName("com.github.xandergos.terraindiffusionmc.pipeline.OracleBiomeClassifier");
        oracle=original.getMethod("classify",float[].class,float[].class,int.class,int.class,float[].class,int.class,int.class,float.class,byte[].class,boolean[].class);
        vanilla=original.getDeclaredMethod("classifyVanilla",TerrainSample.class); vanilla.setAccessible(true);
        Snapshot<String> snapshot=load(Path.of(args[0]));
        randomDerived(snapshot); boundaries(snapshot); actualGrid(snapshot); overridesAndNone(snapshot); adapterContract(); laterNonfiniteInputs(snapshot); enabledEmptyClassification();
        System.out.println("PASS: catalog bridge differential checks="+checks);
    }
}
