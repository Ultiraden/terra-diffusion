package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import java.util.function.Supplier;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Pure registry controller. Exposed metadata and every owning source read one atomic record.
 * Platform invokes commit ONLY after exact staged aggregate reload success. */
public final class RegistryClassificationController<B> {
    public record State<B>(Snapshot<B> catalog,boolean enabled,long revision,Map<Object,WorldClassificationPublication<B>> worlds){
        public State{worlds=Map.copyOf(worlds);}
    }
    public record Commit<B>(boolean accepted,State<B> state,List<String> diagnostics){public Commit{diagnostics=List.copyOf(diagnostics);}}
    private record Spec<B>(long seed,int scale,SavedSelectionPolicy policy,BiomePalette<B> palette){}
    private final Map<Object,Spec<B>> bindings=new HashMap<>();
    private volatile State<B> current=new State<>(new Snapshot<>(false,List.of()),false,0,Map.of());
    public State<B> state(){return current;}
    public final class Handle implements Supplier<WorldClassificationPublication<B>> {
        private final Object token;
        private Handle(Object token){this.token=token;}
        public WorldClassificationPublication<B> get(){
            State<B> observed=current;WorldClassificationPublication<B> publication=observed.worlds().get(token);
            if(publication==null)throw new IllegalStateException("owning classification binding was released");return publication;
        }
    }
    /** Initial semantic error is an inactive owning session; metadata remains diagnosable. */
    public synchronized Handle bind(long seed,int scale,SavedSelectionPolicy policy,BiomePalette<B> palette){
        State<B> before=current;Object token=new Object();
        var publication=WorldClassificationPublication.build(seed,scale,policy,palette,before.catalog(),before.enabled(),before.revision(),false);
        Map<Object,WorldClassificationPublication<B>> worlds=new HashMap<>(before.worlds());worlds.put(token,publication);
        bindings.put(token,new Spec<>(seed,scale,policy,palette));
        current=new State<>(before.catalog(),before.enabled(),before.revision(),worlds);return new Handle(token);
    }
    /** Build all immutable programs under the binding lock, then perform exactly one write.
     * Disabled proposals also validate every resolved target. No partial catalog/palette commit. */
    public synchronized Commit<B> commit(Snapshot<B> catalog,boolean enabled){
        Objects.requireNonNull(catalog);State<B> before=current;
        if(!catalog.active())return new Commit<>(false,before,List.of("catalog proposal is not validated active metadata"));
        long revision=Math.addExact(before.revision(),1);Map<Object,WorldClassificationPublication<B>> worlds=new HashMap<>();List<String> errors=new ArrayList<>();
        for(var binding:bindings.entrySet()){
            Spec<B> s=binding.getValue();
            try{worlds.put(binding.getKey(),WorldClassificationPublication.build(s.seed(),s.scale(),s.policy(),s.palette(),catalog,enabled,revision,true));}
            catch(IllegalArgumentException e){errors.add(e.getMessage());}
        }
        if(!errors.isEmpty()){errors.sort(String::compareTo);return new Commit<>(false,before,errors);}
        State<B> next=new State<>(catalog,enabled,revision,worlds);current=next;return new Commit<>(true,next,List.of());
    }
    /** Parse/aggregate failure does not touch any publication. */
    public Commit<B> reject(List<String> diagnostics){return new Commit<>(false,current,diagnostics);}
    public synchronized void release(Handle handle){
        if(!bindings.containsKey(handle.token))throw new IllegalArgumentException("binding not owned by this controller or already released");
        State<B> before=current;Map<Object,WorldClassificationPublication<B>> worlds=new HashMap<>(before.worlds());worlds.remove(handle.token);bindings.remove(handle.token);
        current=new State<>(before.catalog(),before.enabled(),before.revision(),worlds);
    }
}
