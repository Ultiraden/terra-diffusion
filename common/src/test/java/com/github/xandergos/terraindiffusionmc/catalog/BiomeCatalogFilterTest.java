package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogFilter.*;

/** Generic contract checks independent of TD, provider names and Minecraft. */
public final class BiomeCatalogFilterTest {
    private static int checks;
    private static final String BASE="{\"schema_version\":1,\"biome\":\"fixture:registered\",\"eligibility\":{\"environments\":[\"land\"]}}";
    private static void check(boolean value,String reason) { checks++; if(!value) throw new AssertionError(reason); }
    private static void match(Entry entry,Context context,boolean expected,String reason) {
        check(eligible(entry,context)==expected,"generic "+reason);
        check(new Compiled(entry).eligible(context)==expected,"compiled "+reason);
    }
    public static void main(String[] args) {
        Entry unconstrained=BiomeCatalogLoader.parse("fixture:a",BASE);
        Values empty=new Values(Environment.land,Map.of(),Map.of());
        match(unconstrained,empty,true,"unconstrained missing inputs");
        for(Environment environment:Environment.values()) match(unconstrained,new Values(environment,Map.of(),Map.of()),environment==Environment.land,"environment "+environment);
        double threshold=(double)0.8f;
        for(boolean minInclusive:new boolean[]{false,true}) for(boolean maxInclusive:new boolean[]{false,true}) {
            Entry entry=new Entry("fixture:a","fixture:registered",Set.of(),Set.of(Environment.land),
                Map.of(Field.tree_moisture,new Range(threshold,2d,minInclusive,maxInclusive)),Map.of(),new Selection(1,0,false));
            double[] values={Math.nextDown(threshold),threshold,Math.nextUp(threshold),Math.nextDown(2d),2d,Math.nextUp(2d),Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY};
            for(int i=0;i<values.length;i++) {
                boolean expected=switch(i) { case 1 -> minInclusive; case 2,3 -> true; case 4 -> maxInclusive; default -> false; };
                match(entry,new Values(Environment.land,Map.of(Field.tree_moisture,values[i]),Map.of()),expected,"strict endpoints "+i+"/"+minInclusive+"/"+maxInclusive);
            }
            match(entry,empty,false,"missing constrained number");
        }
        for(Field field:List.of(Field.physical_bathymetry_m,Field.water_depth_blocks)) {
            Entry future=new Entry("fixture:a","fixture:registered",Set.of(),Set.of(Environment.land),Map.of(field,new Range(0d,null,true,true)),Map.of(),new Selection(1,0,false));
            match(future,empty,false,"unavailable "+field);
            match(future,new Values(Environment.land,Map.of(field,0d),Map.of()),true,"authoritative supplied "+field);
            check(unavailable(future,empty).equals(List.of(field.name())),"diagnostic "+field);
        }
        for(boolean required:new boolean[]{false,true}) for(Predicate predicate:Predicate.values()) {
            Entry entry=new Entry("fixture:a","fixture:registered",Set.of(),Set.of(Environment.land),Map.of(),Map.of(predicate,required),new Selection(1,0,false));
            match(entry,empty,false,"missing constrained state "+predicate+"="+required);
            match(entry,new Values(Environment.land,Map.of(),Map.of(predicate,required)),true,"matching state "+predicate);
            match(entry,new Values(Environment.land,Map.of(),Map.of(predicate,!required)),false,"opposite state "+predicate);
        }
        Entry zero=new Entry("fixture:z","fixture:registered",Set.of(),Set.of(Environment.land),Map.of(),Map.of(),new Selection(0,Integer.MAX_VALUE,true));
        Snapshot<String> snapshot=new Snapshot<>(true,List.of(new Candidate<>(zero,"resolved:z"),new Candidate<>(unconstrained,"resolved:a")));
        List<Candidate<String>> candidates=filter(snapshot,empty);
        check(candidates.stream().map(c->c.definition().id()).toList().equals(List.of("fixture:a","fixture:z")),"ASCII deterministic, zero weight remains hard-eligible");
        check(candidates.get(1).biome().equals("resolved:z"),"resolved holder preserved");
        try { candidates.clear(); throw new AssertionError("mutable candidates"); } catch(UnsupportedOperationException expected) { checks++; }
        check(filter(new Snapshot<>(false,snapshot.entries()),empty).isEmpty(),"inactive generic filter");
        check(filter(new Snapshot<>(true,List.of()),empty).isEmpty(),"active empty generic filter");
        Map<Field,Double> numbers=new EnumMap<>(Field.class); numbers.put(Field.temperature_c,15d);
        Map<Predicate,Boolean> states=new EnumMap<>(Predicate.class); states.put(Predicate.submerged,false);
        Values captured=new Values(Environment.land,numbers,states); numbers.clear(); states.clear();
        check(captured.number(Field.temperature_c)==15d && captured.stateCode(Predicate.submerged)==0,"immutable input capture");
        check(Double.isNaN(captured.number(Field.physical_bathymetry_m)),"missing primitive number is NaN");
        check(captured.stateCode(Predicate.coast)==-1,"missing primitive state explicit");
        System.out.println("PASS: generic catalog filtering checks="+checks);
    }
}
