package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogLoader.*;

/** Dependency-free unit suite; assertions are explicit and do not depend on -ea. */
public final class BiomeCatalogTest {
    private static int checks;
    private static final String BASE = "{\"schema_version\":1,\"biome\":\"minecraft:plains\",\"eligibility\":{\"environments\":[\"land\"]}}";
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }
    private static void reject(String json) {
        checks++;
        try { parse("fixture:test", json); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("accepted malformed entry: " + json);
    }
    private static String range(String field, String json) { return BASE.replace("[\"land\"]", "[\"land\"],\"ranges\":{\"" + field + "\":" + json + "}"); }
    private static String predicates(String json) { return BASE.replace("[\"land\"]", "[\"land\"],\"predicates\":" + json); }
    private static Resource resource(String id, String pack, int priority, String json) { return new Resource(id, pack, priority, json); }
    public static void main(String[] args) {
        Entry basic = parse("fixture:test", BASE);
        check(basic.selection().equals(new Selection(1, 0, false)), "selection defaults");
        check(basic.ranges().isEmpty() && basic.predicates().isEmpty() && basic.requiredMods().isEmpty(), "unconstrained defaults");
        for (String json : List.of(
            BASE.replace("1,", "2,"), BASE.replace("1,", "\"1\","), BASE.replace("1,", "null,"),
            BASE.replace("minecraft:plains", "plains"), BASE.replace("minecraft:plains", "Minecraft:plains"),
            BASE.replace("minecraft:plains", "minecraft:../plains"), BASE.replace("minecraft:plains", "minecraft:a//b"),
            BASE.replace("minecraft:plains", "minecraft:plains\\n"), BASE.replace("[\"land\"]", "[]"),
            BASE.replace("minecraft:plains", "minecraft:plains\\u+123"), BASE.replace("minecraft:plains", "minecraft:plains\\u-123"),
            BASE.replace("[\"land\"]", "[\"cave\"]"), BASE.replace("[\"land\"]", "[\"land\",\"land\"]"),
            BASE.replace("\"schema_version\":1,", ""), BASE.replace("\"schema_version\":1,", "\"unknown\":0,\"schema_version\":1,"),
            BASE.replace("\"schema_version\":1,", "\"schema_version\":1,\"schema_version\":1,"),
            BASE.replace("\"schema_version\":1,", "\"schema_version\":1,\"schema_versi\\u006fn\":1,"),
            BASE.replace("{\"schema_version", "{/*comment*/\"schema_version"), BASE + " garbage",
            BASE.replace("1,", "01,"), BASE.replace("1,", "+1,"), BASE.replace("1,", "NaN,"),
            BASE.replace("[\"land\"]", "[\"land\"],\"script\":\"true\""),
            BASE.replace("\"eligibility\"", "\"requires_mods\":[\"Invalid-mod\"],\"eligibility\""),
            BASE.replace("\"eligibility\"", "\"requires_mods\":[\"terralith\",\"terralith\"],\"eligibility\""))) reject(json);
        for (String r : List.of("{}", "{\"min\":2,\"max\":1}", "{\"min\":1,\"max\":1,\"max_inclusive\":false}",
            "{\"min\":null}", "{\"min\":1e309}", "{\"min\":\"0\"}", "{\"min\":0,\"max_inclusive\":true}",
            "{\"min\":0,\"min_inclusive\":1}", "{\"min\":0,\"extra\":0}")) reject(range("classification_elevation_m", r));
        for (String f : List.of("precipitation_mm", "classification_slope", "aridity", "tree_moisture", "effective_tree_moisture", "growing_season_days", "physical_bathymetry_m", "water_depth_blocks")) reject(range(f, "{\"min\":-1}"));
        reject(range("growing_season_days", "{\"max\":366}"));
        reject(range("depth", "{\"min\":0}"));
        for (String p : List.of("{\"trees_none\":true,\"trees_dense\":true}", "{\"trees_none\":false,\"trees_sparse\":false,\"trees_forest\":false,\"trees_dense\":false,\"trees_rainforest\":false}",
            "{\"slope_medium\":true,\"slope_bare\":true}", "{\"barren\":false,\"too_arid\":true}", "{\"barren\":false,\"too_cold\":true}",
            "{\"barren\":true,\"too_arid\":false,\"too_cold\":false}", "{\"has_snow\":null}", "{\"unknown\":true}")) reject(predicates(p));
        reject(predicates("{\"coast\":false}").replace("land", "coast"));
        for (String s : List.of("{\"weight\":-1}", "{\"weight\":1e309}", "{\"priority\":0.5}", "{\"priority\":2147483648}", "{\"priority\":-2147483649}", "{\"fallback\":null}", "{\"rarity\":1}")) reject(BASE.replace("\"eligibility\"", "\"selection\":" + s + ",\"eligibility\""));
        check(parse("fixture:a/b", range("temperature_seasonality", "{\"min\":-10}")).ranges().size() == 1, "negative unclamped seasonality");
        check(parse("fixture:test", range("precipitation_cv", "{\"min\":-10}")).ranges().size() == 1, "negative unclamped CV");
        Entry singleton = parse("fixture:test", range("classification_elevation_m", "{\"min\":1,\"max\":1}"));
        check(singleton.ranges().get(Field.classification_elevation_m).matches(OptionalDouble.of(1)), "inclusive singleton");
        Entry future = parse("fixture:test", range("physical_bathymetry_m", "{\"min\":600}"));
        check(!future.ranges().get(Field.physical_bathymetry_m).matches(OptionalDouble.empty()), "missing future field fails closed");
        check(!future.ranges().get(Field.physical_bathymetry_m).matches(OptionalDouble.of(Double.NaN)), "nonfinite context fails closed");
        check(!parse("fixture:test", predicates("{\"submerged\":false}")).predicateMatches(Predicate.submerged, Optional.empty()), "missing false predicate fails closed");
        check(basic.predicateMatches(Predicate.submerged, Optional.empty()), "unconstrained unavailable field permitted");
        for (String p : List.of("{\"barren\":true,\"too_arid\":false}", "{\"barren\":false,\"too_cold\":false}", "{\"trees_none\":false}")) check(parse("fixture:test", predicates(p)) != null, "satisfiable partial predicates");
        double threshold = (double)0.8f;
        Entry exact = parse("fixture:test", range("tree_moisture", "{\"min\":0.800000011920929,\"min_inclusive\":false}"));
        Range bound = exact.ranges().get(Field.tree_moisture);
        check(bound.min() == threshold && !bound.matches(OptionalDouble.of(threshold)), "exact source float endpoint");
        check(bound.matches(OptionalDouble.of(Math.nextUp(0.8f))) && !bound.matches(OptionalDouble.of(Math.nextDown(0.8f))), "adjacent float endpoints");
        BiomeCatalogLoader<String> loader = new BiomeCatalogLoader<>();
        check(!loader.snapshot().active(), "initial inactive");
        var failed = loader.reload(List.of(resource("fixture:a", "bad", 0, "{}")), Set.of(), Optional::of);
        check(!failed.accepted() && !failed.snapshot().active(), "initial invalid inactive");
        var resources = List.of(resource("fixture:z", "low", 0, "{}"), resource("fixture:z", "high", 1, BASE), resource("fixture:a", "low", 0, BASE));
        var valid = loader.reload(resources, Set.of(), Optional::of);
        check(valid.accepted() && valid.snapshot().active() && valid.snapshot().entries().size() == 2, "winning resource overrides invalid lower file");
        check(valid.snapshot().entries().getFirst().definition().id().equals("fixture:a"), "ASCII ordering");
        var reversed = new ArrayList<>(resources); Collections.reverse(reversed);
        check(loader.reload(reversed, Set.of(), Optional::of).snapshot().equals(valid.snapshot()), "iteration-independent load");
        check(valid.snapshot().byId("fixture:z").isPresent() && valid.snapshot().byEnvironment(Environment.land).size() == 2, "snapshot queries");
        try { valid.snapshot().entries().clear(); throw new AssertionError("mutable snapshot"); } catch (UnsupportedOperationException e) { checks++; }
        try { valid.snapshot().entries().getFirst().definition().environments().clear(); throw new AssertionError("mutable definition"); } catch (UnsupportedOperationException e) { checks++; }
        Snapshot<String> before = loader.snapshot();
        check(!loader.reload(List.of(resource("fixture:a", "high", 1, BASE), resource("fixture:bad", "high", 1, "{}")), Set.of(), Optional::of).accepted() && loader.snapshot() == before, "failed reload retains entire previous snapshot");
        check(!loader.reload(List.of(resource("fixture:a", "same", 1, BASE), resource("fixture:a", "same", 1, BASE)), Set.of(), Optional::of).accepted(), "duplicate entry in pack");
        String optional = BASE.replace("\"eligibility\"", "\"requires_mods\":[\"terralith\"],\"eligibility\"");
        var skipped = loader.reload(List.of(resource("fixture:optional", "test", 0, optional), resource("fixture:missing", "test", 0, BASE.replace("minecraft:plains", "missing:biome"))), Set.of(), id -> { if (id.equals("missing:biome")) return Optional.empty(); throw new AssertionError("absent mod must not resolve registry"); });
        check(skipped.accepted() && skipped.snapshot().entries().isEmpty() && skipped.diagnostics().size() == 2, "mod and registry skips safe");
        check(skipped.diagnostics().getFirst().severity() == Severity.WARNING, "missing registry ID diagnosed");
        check(!loader.reload(List.of(resource("fixture:optional", "test", 0, optional.replace("1,", "2,"))), Set.of(), Optional::of).accepted(), "absent mod cannot hide malformed data");
        String large = BASE.replace("\"eligibility\"", "\"selection\":{\"weight\":1e308},\"eligibility\"");
        check(!loader.reload(List.of(resource("fixture:a", "test", 0, large), resource("fixture:b", "test", 0, large)), Set.of(), Optional::of).accepted(), "finite individual weights overflowing sum reject");
        check(loader.reload(List.of(), Set.of(), Optional::of).accepted() && loader.snapshot().active(), "valid empty catalog active with no candidate; no selection supplied");
        Snapshot<String> current = loader.snapshot();
        var staged = loader.propose(List.of(resource("fixture:a", "test", 0, BASE)), Set.of(), Optional::of);
        check(staged.accepted() && loader.snapshot() == current && staged.snapshot().entries().size() == 1, "validation stages without activation");
        var invalidStaged = loader.propose(List.of(resource("fixture:a", "test", 0, "{}")), Set.of(), Optional::of);
        loader.activate(invalidStaged);
        check(loader.snapshot() == current, "invalid staged proposal cannot publish");
        loader.activate(staged);
        check(loader.snapshot() == staged.snapshot(), "successful platform lifecycle can atomically publish");
        var pointer = loader.propose(List.of(resource("fixture:a", "test", 0, BASE.replace("\"schema_version\"", "\"a/b~c\":0,\"schema_version\""))), Set.of(), Optional::of);
        check(pointer.diagnostics().getFirst().pointer().equals("/a~1b~0c"), "semantic diagnostics escape JSON pointer keys");
        System.out.println("PASS " + checks + " catalog parser/schema/snapshot/query/precedence/reload checks");
    }
}
