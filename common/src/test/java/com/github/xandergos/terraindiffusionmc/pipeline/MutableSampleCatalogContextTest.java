package com.github.xandergos.terraindiffusionmc.pipeline;

import java.lang.reflect.*;
import java.util.*;
import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog;

/** Primitive selection view must equal the existing captured f32 adapter exactly. */
public final class MutableSampleCatalogContextTest {
    public static void main(String[] args)throws Exception{
        var mutable=new MutableSampleCatalogContext();var sample=new TerrainSample();var random=new SplittableRandom(42920261013L);long checks=0;
        java.lang.reflect.Field[] fields=TerrainSample.class.getFields();
        for(int i=0;i<10000;i++){
            for(var f:fields){if(f.getType()==float.class)f.setFloat(sample,Float.intBitsToFloat(random.nextInt()));else if(f.getType()==boolean.class)f.setBoolean(sample,random.nextBoolean());else f.setInt(sample,random.nextInt());}
            for(boolean wet:new boolean[]{false,true}){
                mutable.update(sample,wet);var captured=SampleCatalogContext.from(sample,wet);
                if(mutable.environment()!=captured.environment())throw new AssertionError("environment priority changed");checks++;
                for(var field:BiomeCatalog.Field.values()){
                    if(Double.doubleToRawLongBits(mutable.number(field))!=Double.doubleToRawLongBits(captured.number(field))
                       ||mutable.numeric(field).isPresent()!=captured.numeric(field).isPresent())throw new AssertionError("numeric f32 promotion/availability changed: "+field);
                    checks++;
                }
                for(var p:BiomeCatalog.Predicate.values()){
                    if(mutable.stateCode(p)!=captured.stateCode(p)||!mutable.state(p).equals(captured.state(p)))throw new AssertionError("state changed: "+p);checks++;
                }
            }
        }
        System.out.println("PASS "+checks+" mutable/captured context checks, including raw finite/nonfinite f32 bits");
    }
}
