package com.github.xandergos.terraindiffusionmc.catalog;

import java.nio.file.*;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Standalone pure checks. Statistical limits are declared before observations.
 * No Minecraft world, registry, provider, native inference or GPU is exercised. */
public final class CoherentBiomeSelectorTest {
    private static long checks, sink;
    private static final long[] SEEDS={0,1,-1,0x0123456789abcdefL,0xfedcba9876543210L,
        Long.MIN_VALUE,Long.MAX_VALUE,0x0000000100000000L,0x76543210deadbeefL,42920261011L};
    private static final int SITE_SAMPLES=100000, FAMILY_COMPARISONS=200;
    private static final double FAMILY_ALPHA=.001;
    private static final double EPSILON=Math.sqrt(Math.log(2.0*FAMILY_COMPARISONS/FAMILY_ALPHA)/(2*SITE_SAMPLES));
    private static final List<Object> FREQUENCIES=new ArrayList<>();
    private static final Predicate[] PREDICATES=Predicate.values();
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    private static void rejects(Runnable r,String message){boolean thrown=false;try{r.run();}catch(IllegalArgumentException e){thrown=true;}check(thrown,message);}
    private static final class Context implements BiomeCatalogFilter.Context {
        Environment environment=Environment.land;
        final double[] numbers=new double[Field.values().length];
        final int[] states=new int[Predicate.values().length];
        Context(){Arrays.fill(numbers,Double.NaN);Arrays.fill(states,-1);}
        public Environment environment(){return environment;}
        public OptionalDouble numeric(Field f){double v=numbers[f.ordinal()];return Double.isNaN(v)?OptionalDouble.empty():OptionalDouble.of(v);}
        public Optional<Boolean> state(Predicate p){int v=states[p.ordinal()];return v<0?Optional.empty():Optional.of(v==1);}
        public double number(Field f){return numbers[f.ordinal()];}
        public int stateCode(Predicate p){return states[p.ordinal()];}
        void fixture(int x,int z){
            environment=((x+z)&31)==0?Environment.ocean:Environment.land;
            numbers[Field.temperature_c.ordinal()]=-25+(x%91);
            numbers[Field.precipitation_mm.ordinal()]=100+(z%31)*100;
            numbers[Field.classification_elevation_m.ordinal()]=((x*13+z*7)&2047)-64;
            numbers[Field.classification_slope.ordinal()]=(x&31)/20.0;
            numbers[Field.aridity.ordinal()]=(z&15)/10.0;
            numbers[Field.tree_moisture.ordinal()]=(z&31)/10.0;
            numbers[Field.effective_tree_moisture.ordinal()]=(z&31)/10.0;
            numbers[Field.growing_season_days.ordinal()]=x%366;
            numbers[Field.summer_max_c.ordinal()]=-10+(x%71);
            numbers[Field.temperature_seasonality.ordinal()]=x%40;
            numbers[Field.precipitation_cv.ordinal()]=(z&31)/10.0;
            for(Predicate p:PREDICATES)states[p.ordinal()]=((x+z+p.ordinal())&7)==0?1:0;
            // Future authoritative depth/bathymetry remain missing, not invented zero.
        }
    }
    private static Candidate<String> candidate(String id,double weight,int priority,boolean fallback){
        return new Candidate<>(new Entry("fixture:"+id,"fixture:biome_"+id,Set.of(),Set.of(Environment.land),Map.of(),Map.of(),new Selection(weight,priority,fallback)),new String("holder:"+id));
    }
    private static CoherentBiomeSelector<String> selector(List<Candidate<String>> c,long seed){return new CoherentBiomeSelector<>(new Snapshot<>(true,c),seed,CoherentBiomeSelector.Config.defaults());}
    private static void semantics(){
        Context c=new Context();Candidate<String> zero=candidate("zero",0,Integer.MAX_VALUE,false), normal=candidate("normal",1,Integer.MIN_VALUE,false), fallback=candidate("fallback",1,Integer.MAX_VALUE,true);
        var s=selector(List.of(fallback,zero,normal),0);var scratch=s.scratch(-1,-1,2,2);
        check(s.select(c,0,0,scratch)==normal,"normal beats any fallback; zero cannot win priority");
        check(scratch.decision().priority()==Integer.MIN_VALUE&&!scratch.decision().fallback(),"signed priority exact");
        check(s.select(c,0,0,scratch).biome()==normal.biome(),"resolved biome object identity retained");
        var high=candidate("high",1,Integer.MAX_VALUE,false);var highSelector=selector(List.of(normal,high),0);
        check(highSelector.select(c,0,0,highSelector.scratch(0,0,1,1))==high,"highest normal priority only");
        var oceanHigh=new Candidate<>(new Entry("fixture:ocean_high","fixture:ocean",Set.of(),Set.of(Environment.ocean),Map.of(),Map.of(),new Selection(1,Integer.MAX_VALUE,false)),"resolved ocean");
        var environmentalPriority=selector(List.of(oceanHigh,normal),0);
        check(environmentalPriority.select(c,0,0,environmentalPriority.scratch(0,0,1,1))==normal,"ineligible high priority cannot shadow lower admitted tier");
        var fallbackSelector=selector(List.of(zero,fallback),0);var fs=fallbackSelector.scratch(0,0,1,1);
        check(fallbackSelector.select(c,0,0,fs)==fallback&&fs.decision().fallback(),"fallback after no positive normal");
        var empty=selector(List.of(zero),0);var es=empty.scratch(0,0,1,1);
        check(empty.select(c,0,0,es)==null&&es.noMatch()==CoherentBiomeSelector.NoMatch.NO_ELIGIBLE_POSITIVE_CANDIDATE,"typed positive-empty result");
        var inactive=new CoherentBiomeSelector<>(new Snapshot<>(false,List.of(normal)),0,CoherentBiomeSelector.Config.defaults());var is=inactive.scratch(0,0,1,1);
        check(inactive.select(c,0,0,is)==null&&is.noMatch()==CoherentBiomeSelector.NoMatch.INACTIVE_CATALOG,"typed inactive result");
        rejects(()->selector(List.of(normal,normal),0),"duplicate entry rejects");
        rejects(()->selector(List.of(candidate("nan",Double.NaN,0,false)),0),"nonfinite weight rejects");
        rejects(()->selector(List.of(candidate("negative",-1,0,false)),0),"negative weight rejects");
        rejects(()->selector(List.of(candidate("a",Double.MAX_VALUE,0,false),candidate("b",Double.MAX_VALUE,0,true)),0),"global finite sum contract");
        rejects(()->new CoherentBiomeSelector.Config(15),"spacing lower bound");rejects(()->new CoherentBiomeSelector.Config(65537),"spacing upper bound");
        rejects(()->s.scratch(-30000001,0,0,1),"world lower bound");rejects(()->s.scratch(0,0,30000002,1),"world upper bound");
        rejects(()->s.scratch(-30000000,-30000000,30000001,30000001),"bounded scratch budget");
        rejects(()->s.select(c,2,0,scratch),"point bounds");rejects(()->s.select(c,0,0,highSelector.scratch(0,0,1,1)),"foreign scratch ownership");
        check(s.scratch(0,0,1024,1024).siteCount()==9,"aligned 1024 scratch caches nine sites");
        check(s.scratch(1,1,1025,1025).siteCount()==16,"unaligned scratch caches sixteen sites");
        Candidate<String> gated=new Candidate<>(new Entry("fixture:gated","fixture:any_arbitrary_id",Set.of(),Set.of(Environment.land),Map.of(Field.temperature_c,new Range(0d,10d,false,true)),Map.of(Predicate.has_snow,false),new Selection(1,0,false)),"resolved arbitrary holder");
        var g=selector(List.of(gated),0);var gs=g.scratch(0,0,1,1);
        for(double v:new double[]{Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1,0,Math.nextUp(0d),10,Math.nextUp(10d)}){
            for(int state:new int[]{-1,0,1})for(Environment env:Environment.values()){
                c.numbers[Field.temperature_c.ordinal()]=v;c.states[Predicate.has_snow.ordinal()]=state;c.environment=env;
                boolean expected=BiomeCatalogFilter.eligible(gated.definition(),c);
                check((g.select(c,0,0,gs)!=null)==expected,"exact hard filter numeric/state/environment endpoints");
                check(g.eligible(c).equals(BiomeCatalogFilter.filter(new Snapshot<>(true,List.of(gated)),c)),"debug admission equals reference");
            }
        }
        var huge=selector(List.of(candidate("a",1,0,false),candidate("z",Double.MIN_VALUE,0,false)),0);var hs=huge.scratch(0,0,1,1);
        check(huge.select(new Context(),0,0,hs)!=null&&hs.collapsedPositiveIntervals()==1&&hs.belowQuantileResolution()==1,"finite tiny interval diagnostic");
        check(CoherentBiomeSelector.samplingTarget(Math.nextDown(1d),Double.MIN_VALUE)<Double.MIN_VALUE,"endpoint clamp when product rounds to total");
        check(CoherentBiomeSelector.samplingTarget(0,Double.MAX_VALUE)==0,"CDF zero endpoint");
        rejects(()->CoherentBiomeSelector.samplingTarget(1,1),"CDF upper endpoint forbidden");
    }
    private static void geometry(){
        Context c=new Context();var candidates=List.of(candidate("a",1,0,false),candidate("b",3,0,false));
        for(int h:new int[]{16,1024,2048,65536})for(long seed:SEEDS){
            var s=new CoherentBiomeSelector<>(new Snapshot<>(true,candidates),seed,new CoherentBiomeSelector.Config(h));var random=new SplittableRandom(seed);
            for(int i=0;i<160;i++){
                long x=i<8?new long[]{-30000000,-2049,-1024,-1,0,1023,1024,30000000}[i]:random.nextLong(-30000000,30000001);
                long z=i<8?-x:random.nextLong(-30000000,30000001);var scratch=s.scratch(x,z,x+1,z+1);s.select(c,x,z,scratch);var actual=scratch.decision().site();
                long cx=Math.floorDiv(x,h),cz=Math.floorDiv(z,h),bx=Long.MAX_VALUE,bz=Long.MAX_VALUE;double best=Double.POSITIVE_INFINITY;
                for(long iz=cz-3;iz<=cz+3;iz++)for(long ix=cx-3;ix<=cx+3;ix++){
                    var site=s.site(ix,iz);double dx=x-site.x(),dz=z-site.z(),distance=dx*dx+dz*dz;
                    check(site.x()>=(ix+.25)*h&&site.x()<(ix+.75)*h&&site.z()>=(iz+.25)*h&&site.z()<(iz+.75)*h,"bounded quarter-cell jitter including negatives");
                    if(distance<best||(distance==best&&(ix<bx||(ix==bx&&iz<bz)))){best=distance;bx=ix;bz=iz;}
                }
                check(actual.cellX()==bx&&actual.cellZ()==bz,"3x3 nearest equals independent 7x7 exhaustive search");
                check(scratch.distanceEvaluations()==9,"nine distances per point");
            }
        }
        var s=selector(candidates,0xfedcba9876543210L);var reverse=selector(List.of(candidates.get(1),candidates.get(0)),0xfedcba9876543210L);var whole=s.scratch(-2100,-2100,2101,2101);var other=reverse.scratch(-2100,-2100,2101,2101);
        List<long[]> points=new ArrayList<>();for(int z=-2100;z<=2100;z+=67)for(int x=-2100;x<=2100;x+=61)points.add(new long[]{x,z});
        Map<String,String> saved=new HashMap<>();for(var p:points){var chosen=s.select(c,p[0],p[1],whole);saved.put(p[0]+","+p[1],chosen.definition().id());check(reverse.select(c,p[0],p[1],other).definition().id().equals(chosen.definition().id()),"ASCII input order invariance");}
        Collections.reverse(points);for(var p:points){var one=s.scratch(p[0],p[1],p[0]+1,p[1]+1);check(s.select(c,p[0],p[1],one).definition().id().equals(saved.get(p[0]+","+p[1])),"crop and query-order invariance");}
        var low=selector(candidates,0);var high=selector(candidates,0x100000000L);check(!low.site(0,0).equals(high.site(0,0)),"high seed bits participate");
        // Golden bits are checked against an independent Python integer-arithmetic fixture by the runner.
        for(long seed:new long[]{0,-1,0x0123456789abcdefL})for(long[] p:new long[][]{{0,0},{-1,-1},{29296,-29297}}){var a=selector(candidates,seed).site(p[0],p[1]);System.out.println("GOLDEN "+seed+" "+p[0]+" "+p[1]+" "+Long.toUnsignedString(Double.doubleToLongBits(a.x()))+" "+Long.toUnsignedString(Double.doubleToLongBits(a.z()))+" "+Long.toUnsignedString(Double.doubleToLongBits(a.quantile())));}
    }
    private static void frequency(){
        Context c=new Context();
        double large=0x1.0p1020;
        double[][] weights={{0,1,3,9},{1,3},{Double.MIN_VALUE,3*Double.MIN_VALUE},{large,3*large},{1,1},{1,9},{2,3},{1,2,3}};
        String[] names={"zero-1-3-9","unit-1-3","subnormal-1-3","large-1-3","unit-1-1","unit-1-9","doubled-2-3","overlap-1-plus-2-versus-3"};
        for(long seed:SEEDS){
            var alternateSelector=new CoherentBiomeSelector<String>(new Snapshot<>(true,List.of()),seed^0x9e3779b97f4a7c15L,CoherentBiomeSelector.Config.defaults());
            List<CoherentBiomeSelector<String>> selectors=new ArrayList<>();List<CoherentBiomeSelector<String>.Scratch> scratches=new ArrayList<>();long[][] counts=new long[weights.length][];
            for(int t=0;t<weights.length;t++){
                List<Candidate<String>> entries=new ArrayList<>();for(int i=0;i<weights[t].length;i++){
                    var ca=candidate("entry"+i,weights[t][i],0,false);
                    if(t==7&&i<2){var e=ca.definition();ca=new Candidate<>(new Entry(e.id(),"fixture:shared",e.requiredMods(),e.environments(),e.ranges(),e.predicates(),e.selection()),"same resolved biome");}
                    entries.add(ca);
                }selectors.add(selector(entries,seed));counts[t]=new long[weights[t].length];
                if(t==2){entries.add(candidate("entry8",Double.MAX_VALUE,Integer.MAX_VALUE,true));selectors.set(t,selector(entries,seed));}
            }
            long biased=0;
            for(int half=0;half<2;half++){
                scratches.clear();for(var s:selectors)scratches.add(s.scratch(-200*1024L,(-125+half*125)*1024L,200*1024L,half*125*1024L));
                for(int row=0;row<125;row++)for(int col=0;col<400;col++){
                    long cx=col-200,cz=row-125+half*125;var site=selectors.get(1).site(cx,cz);long x=Math.round(site.x()),z=Math.round(site.z());String unit=null;
                    for(int t=0;t<weights.length;t++){
                        var selected=selectors.get(t).select(c,x,z,scratches.get(t));int index=Integer.parseInt(selected.definition().id().substring("fixture:entry".length()));counts[t][index]++;
                        if(t==1)unit=selected.definition().id();if(t==2||t==3)check(selected.definition().id().equals(unit),"exact practical ratio scale invariance");
                        if(t==2)check(scratches.get(t).weightScaleExponent()==1023,"subnormal retained-tier scaling observed");
                    }
                    double alternate=alternateSelector.site(cx,cz).quantile();
                    if((site.quantile()+alternate)*.5<.25)biased++;
                }
            }
            for(int t=0;t<weights.length;t++){
                double total=Arrays.stream(weights[t]).sum();List<Object> categories=new ArrayList<>();
                for(int i=0;i<weights[t].length;i++){
                    double expected=weights[t][i]/total,actual=counts[t][i]/(double)SITE_SAMPLES;
                    check(Math.abs(actual-expected)<=EPSILON,"predeclared frequency bound "+names[t]+" seed "+seed+" category "+i);
                    if(weights[t][i]==0)check(counts[t][i]==0,"zero never selected");
                    categories.add(Map.of("index",i,"count",counts[t][i],"expected",expected,"observed",actual));
                }
                if(t==7)check(Math.abs((counts[t][0]+counts[t][1])/(double)SITE_SAMPLES-.5)<=EPSILON,"overlapping same-biome entries add retained weights");
                FREQUENCIES.add(Map.of("fixture",names[t],"seed",Long.toString(seed),"samples",SITE_SAMPLES,"categories",categories));
            }
            check(counts[6][0]/(double)SITE_SAMPLES-counts[1][0]/(double)SITE_SAMPLES>.10,"doubling weight measurably increases share");
            check(Math.abs(biased/(double)SITE_SAMPLES-.25)>EPSILON,"biased average-uniform CDF negative control is rejected");
            FREQUENCIES.add(Map.of("fixture","biased-average-uniform-control","seed",Long.toString(seed),"expectedIfUniform",.25,"observed",biased/(double)SITE_SAMPLES,"rejected",true));
        }
    }
    private static Map<String,Object> coherence(){
        Context c=new Context();var s=selector(List.of(candidate("a",1,0,false),candidate("b",1,0,false)),1);var scratch=s.scratch(-1024,-1024,1024,1024);String[] previous=new String[2048];long same=0,pairs=0,checkerSame=0;
        for(int z=-1024;z<1024;z++){
            String left=null;for(int x=-1024;x<1024;x++){
                String id=s.select(c,x,z,scratch).definition().id();int i=x+1024;
                if(left!=null){pairs++;if(left.equals(id))same++;}if(previous[i]!=null){pairs++;if(previous[i].equals(id))same++;}
                left=id;previous[i]=id;
                // Positive broken implementation control: per-column checkerboard flips both axes.
                if(x>-1024&&((x+z)&1)==((x-1+z)&1))checkerSame++;
            }
        }
        double agreement=same/(double)pairs;check(agreement>.99,"constant gates have spatial coherence at 1024 world blocks");check(checkerSame==0,"checkerboard broken control has no adjacency coherence");
        return Map.of("width",2048,"height",2048,"adjacentPairs",pairs,"agreement",agreement,"predeclaredMinimum",.99,"checkerboardAgreement",0);
    }
    private static Map<String,Object> actualCatalog(Path root)throws Exception{
        List<BiomeCatalogLoader.Resource> resources=new ArrayList<>();try(var paths=Files.walk(root.resolve("data"))){for(Path p:paths.filter(x->x.toString().endsWith(".json")&&x.toString().contains("biome_catalog")).sorted().toList()){
            Path relative=root.resolve("data").relativize(p);String id=relative.getName(0)+":"+relative.subpath(3,relative.getNameCount()).toString().replace('\\','/').replaceFirst("\\.json$","");resources.add(new BiomeCatalogLoader.Resource(id,"fixture",0,Files.readString(p)));
        }}
        var result=new BiomeCatalogLoader<String>().propose(resources,Set.of("terralith"),id->Optional.of("resolved:"+id));check(result.accepted(),"actual catalog validated");check(result.snapshot().entries().size()==429,"frozen delivered 429 entries");
        var s=new CoherentBiomeSelector<>(result.snapshot(),42920261011L,CoherentBiomeSelector.Config.defaults());check(s.sharedProgramCount()==277,"actual shared eligibility count");
        var compiled=result.snapshot().entries().stream().map(c->new BiomeCatalogFilter.Compiled(c.definition())).toArray(BiomeCatalogFilter.Compiled[]::new);Context c=new Context();var scratch=s.scratch(0,0,1024,1024);
        // Exact admission differential on changing environmental fixture, before benchmarking.
        for(int z=0;z<71;z++)for(int x=0;x<83;x++){
            c.fixture(x,z);check(s.eligible(c).equals(BiomeCatalogFilter.filter(result.snapshot(),c)),"actual 429 compiled sharing preserves admission");var chosen=s.select(c,x,z,scratch);
            check(chosen==null||chosen.definition().selection().weight()>0&&BiomeCatalogFilter.eligible(chosen.definition(),c),"actual catalog selected only positive hard-admitted candidate");
        }
        List<Double> referenceMs=new ArrayList<>(),selectorMs=new ArrayList<>();
        for(int iteration=-2;iteration<5;iteration++){
            long start=System.nanoTime(),count=0;for(int z=0;z<1024;z++)for(int x=0;x<1024;x++){c.fixture(x,z);for(var program:compiled)if(program.eligible(c))count++;}long elapsed=System.nanoTime()-start;sink^=count;if(iteration>=0)referenceMs.add(elapsed/1e6);
            var tile=s.scratch(0,0,1024,1024);start=System.nanoTime();count=0;for(int z=0;z<1024;z++)for(int x=0;x<1024;x++){c.fixture(x,z);var chosen=s.select(c,x,z,tile);if(chosen!=null)count+=chosen.definition().id().length();}elapsed=System.nanoTime()-start;sink^=count;if(iteration>=0)selectorMs.add(elapsed/1e6);
            System.out.println("BENCHMARK iteration "+iteration+" completed");
        }
        Map<String,Object> allocation=new LinkedHashMap<>();var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        if(bean instanceof com.sun.management.ThreadMXBean memory&&memory.isThreadAllocatedMemorySupported()){
            memory.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();var tile=s.scratch(0,0,1024,1024);
            // Warm the measurement loop before observing only this thread's production hot loop.
            for(int z=0;z<64;z++)for(int x=0;x<1024;x++){c.fixture(x,z);var chosen=s.select(c,x,z,tile);if(chosen!=null)sink+=chosen.definition().id().length();}
            long before=memory.getThreadAllocatedBytes(thread),count=0;for(int z=0;z<1024;z++)for(int x=0;x<1024;x++){c.fixture(x,z);var chosen=s.select(c,x,z,tile);if(chosen!=null)count+=chosen.definition().id().length();}long bytes=memory.getThreadAllocatedBytes(thread)-before;sink^=count;
            check(bytes==0,"zero measured allocated bytes in warmed actual-429 primitive selector hot loop");
            allocation.put("supported",true);allocation.put("columns",1048576);allocation.put("allocatedBytes",bytes);allocation.put("bytesPerColumn",bytes/1048576d);allocation.put("excludes","compile, bounded tile scratch, explicit debug Decision and context construction");
        }else allocation.put("supported",false);
        return Map.of("entries",429,"sharedEligibilityPrograms",s.sharedProgramCount(),"tileColumns",1048576,"warmupIterations",2,"measuredIterations",5,"referenceCompiled429AdmissionOnlyMs",referenceMs,"sharedSelectorIncludingGeometryAndWeightedChoiceMs",selectorMs,"hotLoopAllocation",allocation,"scope","Synthetic primitive contexts, no inference or Minecraft; timings are observations, not a runtime acceptance threshold. Admission-only reference is not the legacy bridge.");
    }
    private static String json(Object value){
        if(value instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\"";
        if(value instanceof Map<?,?> m){List<String> entries=new ArrayList<>();for(var e:m.entrySet())entries.add(json(e.getKey().toString())+":"+json(e.getValue()));return "{"+String.join(",",entries)+"}";}
        if(value instanceof Collection<?> c)return "["+String.join(",",c.stream().map(CoherentBiomeSelectorTest::json).toList())+"]";
        return String.valueOf(value);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("catalog root and fresh report path required");Path output=Path.of(args[1]);if(Files.exists(output))throw new IllegalArgumentException("refuse overwrite");
        semantics();System.out.println("STAGE semantics PASS");geometry();System.out.println("STAGE geometry PASS");frequency();System.out.println("STAGE frequency PASS");var coherence=coherence();System.out.println("STAGE coherence PASS");var actual=actualCatalog(Path.of(args[0]));
        Map<String,Object> report=new LinkedHashMap<>();report.put("status","PASS");report.put("checks",checks);report.put("algorithm",CoherentBiomeSelector.ALGORITHM);report.put("spacingWorldBlocks",1024);report.put("statisticalProtocol",Map.of("distinctSitesPerSeed",SITE_SAMPLES,"seeds",Arrays.stream(SEEDS).mapToObj(Long::toString).toList(),"familyComparisonsUpperBound",FAMILY_COMPARISONS,"familyAlpha",FAMILY_ALPHA,"hoeffdingAbsoluteBound",EPSILON,"interpretation","Diagnostic bound assumes ideal independent uniform site labels; deterministic hash tests do not prove probabilistic independence."));report.put("frequencies",FREQUENCIES);report.put("coherence",coherence);report.put("actualCatalogBenchmark",actual);report.put("sink",sink);
        Files.writeString(output,json(report)+"\n",StandardOpenOption.CREATE_NEW);System.out.println("PASS "+checks+" pure checks; report "+output);
    }
}
