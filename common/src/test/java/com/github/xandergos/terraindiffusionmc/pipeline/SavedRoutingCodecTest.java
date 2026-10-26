package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.SavedSelectionPolicy;
import com.github.xandergos.terraindiffusionmc.catalog.minecraft.SavedSelectionPolicyCodec;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.util.List;

/** Actual production codec; no Minecraft world/native execution. */
public final class SavedRoutingCodecTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void rejects(JsonObject value,String why){check(SavedSelectionPolicyCodec.CODEC.parse(JsonOps.INSTANCE,value).error().isPresent(),why);}
    public static void main(String[] args){
        var compatible=SavedSelectionPolicy.generic(1024,List.of("fixture:a","fixture:b"));
        var old=SavedSelectionPolicyCodec.CODEC.encodeStart(JsonOps.INSTANCE,compatible).result().orElseThrow().getAsJsonObject();
        check(old.get("version").getAsInt()==1&&!old.has("routing"),"accepted v1 encoding retains original fields/defaults");
        check(SavedSelectionPolicyCodec.CODEC.parse(JsonOps.INSTANCE,old).result().orElseThrow().equals(compatible),"v1 roundtrip retains compatibility semantics");
        var strict=SavedSelectionPolicy.catalogOnly(1024,compatible.possibleBiomeIds());
        var encoded=SavedSelectionPolicyCodec.CODEC.encodeStart(JsonOps.INSTANCE,strict).result().orElseThrow().getAsJsonObject();
        check(encoded.get("version").getAsInt()==2&&encoded.get("routing").getAsString().equals("catalog-only-v1"),"strict format is explicitly versioned");
        check(SavedSelectionPolicyCodec.CODEC.parse(JsonOps.INSTANCE,encoded).result().orElseThrow().equals(strict),"strict roundtrip retains policy/pool/spacing");
        var bad=encoded.deepCopy();bad.addProperty("version",1);rejects(bad,"strict routing cannot masquerade as v1");
        bad=encoded.deepCopy();bad.remove("routing");rejects(bad,"v2 cannot silently default routing");
        bad=encoded.deepCopy();bad.addProperty("routing","unknown-v9");rejects(bad,"unknown strict routing rejected");
        bad=encoded.deepCopy();bad.addProperty("version",3);rejects(bad,"future saved versions rejected");
        bad=encoded.deepCopy();bad.addProperty("algorithm","unknown");rejects(bad,"routing does not waive geometry version");
        bad=encoded.deepCopy();bad.addProperty("routing",123);rejects(bad,"numeric routing is not a mode");
        bad=old.deepCopy();bad.addProperty("routing",false);rejects(bad,"malformed v1 optional field cannot silently default");
        bad=old.deepCopy();bad.addProperty("routing","compatibility");rejects(bad,"v1 accepts only its original absent routing field");
        System.out.println("PASS saved routing production codec "+checks+" checks; no world/native execution");
    }
}
