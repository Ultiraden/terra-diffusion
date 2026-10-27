package com.github.xandergos.terraindiffusionmc.catalog;

import java.nio.file.*;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Product-data gates and synthetic context reachability; not native/feature evidence. */
public final class VanillaCatalogTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static List<BiomeCatalogLoader.Resource> resources(Path root)throws Exception{
        List<BiomeCatalogLoader.Resource> result=new ArrayList<>();
        try(var paths=Files.walk(root)){
            for(Path p:paths.filter(f->f.toString().endsWith(".json")).sorted().toList()){
                String name=root.relativize(p).toString().replace('\\','/');
                result.add(new BiomeCatalogLoader.Resource("ultira:"+name.substring(0,name.length()-5),"fixture",0,Files.readString(p)));
            }
        }return result;
    }
    private static BiomeCatalogFilter.Values witness(Entry e){
        var n=new EnumMap<Field,Double>(Field.class);for(Field f:Field.values())n.put(f,1d);
        n.put(Field.temperature_c,14d);n.put(Field.precipitation_mm,1500d);n.put(Field.classification_elevation_m,100d);
        n.put(Field.classification_slope,.2);n.put(Field.effective_tree_moisture,.6);n.put(Field.tree_moisture,.7);n.put(Field.aridity,.7);
        for(var p:e.ranges().entrySet()){
            Range r=p.getValue();double value=r.min()==null?r.max()-10:r.max()==null?r.min()+10:(r.min()+r.max())/2;
            if(r.min()!=null&&r.max()!=null&&r.min().equals(r.max()))value=r.min();
            n.put(p.getKey(),value);
        }
        var states=new EnumMap<Predicate,Boolean>(Predicate.class);for(Predicate p:Predicate.values())states.put(p,false);
        if(!e.ranges().containsKey(Field.effective_tree_moisture)){
            if(Boolean.TRUE.equals(e.predicates().get(Predicate.trees_none)))n.put(Field.effective_tree_moisture,.1);
            if(Boolean.TRUE.equals(e.predicates().get(Predicate.trees_sparse)))n.put(Field.effective_tree_moisture,.3);
        }
        double eff=n.get(Field.effective_tree_moisture);
        states.put(Predicate.trees_none,eff<.2);states.put(Predicate.trees_sparse,eff>=.2&&eff<.5);
        states.put(Predicate.trees_forest,eff>=.5&&eff<.8);states.put(Predicate.trees_dense,eff>=.8&&eff<1.3);states.put(Predicate.trees_rainforest,eff>=1.3);
        states.putAll(e.predicates());
        if(Boolean.TRUE.equals(states.get(Predicate.slope_bare))){states.put(Predicate.trees_none,true);for(Predicate p:List.of(Predicate.trees_sparse,Predicate.trees_forest,Predicate.trees_dense,Predicate.trees_rainforest,Predicate.slope_medium))states.put(p,false);}
        return new BiomeCatalogFilter.Values(e.environments().iterator().next(),n,states);
    }
    public static void main(String[] args)throws Exception{
        // Vanilla root, unchanged Terralith root, approved intended-ID text, report output.
        var expected=new TreeSet<>(Files.readAllLines(Path.of(args[2])));check(expected.size()==46,"approved46 exact inventory");
        var vanilla=resources(Path.of(args[0]));var all=new ArrayList<>(vanilla);all.addAll(resources(Path.of(args[1])));
        var loaded=new BiomeCatalogLoader<String>().reload(all,Set.of("terralith"),id->Optional.of(id.intern()));
        check(loaded.accepted(),"production parser accepts full combined candidate: "+loaded.diagnostics());
        var entries=loaded.snapshot().entries().stream().filter(c->c.definition().id().startsWith("ultira:vanilla/")).toList();
        check(entries.size()==50,"exact50 normal resource inventory");
        var actual=new TreeSet<String>();for(var c:entries){actual.add(c.definition().biome());check(c.definition().selection().weight()==1&&c.definition().selection().priority()==0&&!c.definition().selection().fallback(),"unit normal contribution with zero fallback entries");}
        check(actual.equals(expected),"no missing or special/unapproved Vanilla targets");
        var selector=new CoherentBiomeSelector<>(loaded.snapshot(),8872036595677660L,CoherentBiomeSelector.Config.defaults());
        var scratch=selector.scratch(-32768,-32768,32768,32768);var reachable=new TreeSet<String>();var report=new ArrayList<String>();report.add("biome,entry,x,z,scope");
        for(var c:entries){
            Entry e=c.definition();var context=witness(e);check(BiomeCatalogFilter.eligible(e,context),"entry has a synthetic eligibility witness: "+e.id());
            check(entries.stream().filter(v->v.definition().biome().equals(e.biome())&&BiomeCatalogFilter.eligible(v.definition(),context)).count()==1,"split rules contribute once per biome: "+e.id());
            // Every serialized endpoint is tested against the production filter.
            for(var field:e.ranges().entrySet())for(Double bound:Arrays.asList(field.getValue().min(),field.getValue().max()))if(bound!=null){
                float f=bound.floatValue();for(float v:new float[]{Math.nextDown(f),f,Math.nextUp(f)}){
                    var values=new EnumMap<Field,Double>(context.numbers());values.put(field.getKey(),(double)v);
                    boolean expectedMatch=field.getValue().matches(OptionalDouble.of(v));
                    check(BiomeCatalogFilter.eligible(e,new BiomeCatalogFilter.Values(context.environment(),values,context.states()))==expectedMatch,"production endpoint "+e.id()+" "+field.getKey());
                }
            }
            if(reachable.contains(e.biome()))continue;
            outer:for(int z=-30;z<30;z++)for(int x=-30;x<30;x++){
                var choice=selector.select(context,x*1024L,z*1024L,scratch);
                if(choice!=null&&choice.definition().id().equals(e.id())){reachable.add(e.biome());report.add(e.biome()+","+e.id()+","+(x*1024)+","+(z*1024)+",synthetic-context-only");break outer;}
            }
            check(reachable.contains(e.biome()),"weighted selection reaches new Vanilla entry alongside unchanged modded candidates: "+e.biome());
        }
        check(reachable.equals(expected),"all46 targets selectable in synthetic contexts");
        Files.write(Path.of(args[3]),report);System.out.println("PASS Vanilla catalog "+checks+" checks; all46 weighted synthetic-context targets reachable; no Minecraft/feature claim");
    }
}
