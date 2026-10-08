package com.github.xandergos.terraindiffusionmc.catalog;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Validates the entire proposal before mod/registry skips or atomic publication. */
public final class BiomeCatalogLoader<B> {
    public record Resource(String id, String pack, int precedence, String json) {}
    public enum Severity { ERROR, WARNING, INFO }
    public record Diagnostic(String id, String pack, String pointer, Severity severity, String reason) {}
    public record Result<B>(boolean accepted, Snapshot<B> snapshot, List<Diagnostic> diagnostics) {
        public Result { diagnostics = List.copyOf(diagnostics); }
    }
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_.-]+(?:/[a-z0-9_.-]+)*");
    private static final Pattern MOD = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private volatile Snapshot<B> snapshot = new Snapshot<>(false, List.of());
    public Snapshot<B> snapshot() { return snapshot; }

    public synchronized Result<B> reload(Collection<Resource> resources, Set<String> mods, Function<String,Optional<B>> registry) {
        Result<B> proposal = propose(resources, mods, registry);
        activate(proposal);
        return proposal;
    }
    /** Validation is side-effect free so a platform can wait for aggregate reload success. */
    public synchronized Result<B> propose(Collection<Resource> resources, Set<String> mods, Function<String,Optional<B>> registry) {
        Objects.requireNonNull(mods); Objects.requireNonNull(registry);
        List<Diagnostic> diagnostics = new ArrayList<>();
        TreeMap<String,Resource> winners = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        for (Resource resource : resources) {
            // Duplicate IDs inside one pack are an error even when another pack overrides them.
            if (!seen.add(resource.pack + "\0" + resource.id)) {
                diagnostics.add(new Diagnostic(resource.id, resource.pack, "/", Severity.ERROR, "duplicate entry in pack"));
            }
            Resource old = winners.get(resource.id);
            if (old == null || resource.precedence > old.precedence) winners.put(resource.id, resource);
            else if (resource.precedence == old.precedence && !resource.pack.equals(old.pack)) {
                diagnostics.add(new Diagnostic(resource.id, resource.pack, "/", Severity.ERROR, "ambiguous pack precedence"));
            }
        }
        List<Entry> parsed = new ArrayList<>();
        double total = 0;
        for (Resource resource : winners.values()) {
            try {
                Entry entry = parse(resource.id, resource.json);
                total += entry.selection().weight();
                if (!Double.isFinite(total)) throw invalid("/selection/weight", "catalog weight sum overflow");
                parsed.add(entry);
            } catch (IllegalArgumentException e) {
                String message = e.getMessage();
                int split = message.indexOf(": ");
                diagnostics.add(new Diagnostic(resource.id, resource.pack, split < 0 ? "/" : message.substring(0, split),
                    Severity.ERROR, split < 0 ? message : message.substring(split + 2)));
            }
        }
        if (diagnostics.stream().anyMatch(d -> d.severity == Severity.ERROR)) return result(false, diagnostics);
        List<Candidate<B>> resolved = new ArrayList<>();
        for (Entry entry : parsed) {
            Resource resource = winners.get(entry.id());
            if (!mods.containsAll(entry.requiredMods())) {
                diagnostics.add(new Diagnostic(entry.id(), resource.pack, "/requires_mods", Severity.INFO, "required mod absent; entry skipped"));
                continue;
            }
            Optional<B> biome = registry.apply(entry.biome());
            if (biome.isEmpty()) {
                diagnostics.add(new Diagnostic(entry.id(), resource.pack, "/biome", Severity.WARNING, "missing biome " + entry.biome() + "; entry skipped"));
                continue;
            }
            resolved.add(new Candidate<>(entry, biome.get()));
        }
        Result<B> sorted = result(true, diagnostics);
        return new Result<>(true, new Snapshot<>(true, resolved), sorted.diagnostics());
    }
    /** Publish only a successfully validated proposal; failed proposals cannot replace state. */
    public synchronized void activate(Result<B> proposal) { if (proposal.accepted()) snapshot = proposal.snapshot(); }
    private Result<B> result(boolean accepted, List<Diagnostic> diagnostics) {
        diagnostics.sort(Comparator.comparing(Diagnostic::id).thenComparing(Diagnostic::pack)
            .thenComparing(Diagnostic::pointer).thenComparing(Diagnostic::reason));
        return new Result<>(accepted, snapshot, diagnostics);
    }
    private static IllegalArgumentException invalid(String pointer, String reason) { return new IllegalArgumentException(pointer + ": " + reason); }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> object(Object value, String pointer, String... allowed) {
        if (!(value instanceof Map<?,?>)) throw invalid(pointer, "expected object");
        Map<String,Object> map = (Map<String,Object>)value;
        Set<String> keys = Set.of(allowed);
        for (String key : map.keySet()) if (!keys.contains(key)) throw invalid(pointer + "/" + key.replace("~", "~0").replace("/", "~1"), "unknown field");
        return map;
    }
    private static Object required(Map<String,Object> map, String key, String pointer) {
        if (!map.containsKey(key)) throw invalid(pointer + "/" + key, "required field");
        return map.get(key);
    }
    private static String string(Object value, String pointer) {
        if (!(value instanceof String s)) throw invalid(pointer, "expected string");
        return s;
    }
    private static String identifier(Object value, String pointer) {
        String s = string(value, pointer);
        if (!ID.matcher(s).matches() || Arrays.stream(s.substring(s.indexOf(':') + 1).split("/")).anyMatch(p -> p.equals(".") || p.equals("..")))
            throw invalid(pointer, "invalid resource ID");
        return s;
    }
    private static double number(Object value, String pointer) {
        if (!(value instanceof BigDecimal n)) throw invalid(pointer, "expected number");
        double v = n.doubleValue();
        if (!Double.isFinite(v)) throw invalid(pointer, "number exceeds finite binary64");
        return v;
    }
    private static boolean bool(Object value, String pointer) {
        if (!(value instanceof Boolean b)) throw invalid(pointer, "expected boolean");
        return b;
    }
    private static List<?> array(Object value, String pointer) {
        if (!(value instanceof List<?> a)) throw invalid(pointer, "expected array");
        return a;
    }
    public static Entry parse(String id, String json) {
        identifier(id, "/resource_id");
        Map<String,Object> root = object(StrictJson.parse(json), "", "schema_version", "biome", "requires_mods", "eligibility", "selection");
        Object version = required(root, "schema_version", "");
        if (!(version instanceof BigDecimal n) || n.compareTo(BigDecimal.ONE) != 0) throw invalid("/schema_version", "unsupported schema version");
        String biome = identifier(required(root, "biome", ""), "/biome");
        Set<String> mods = new HashSet<>();
        if (root.containsKey("requires_mods")) {
            int i = 0;
            for (Object value : array(root.get("requires_mods"), "/requires_mods")) {
                String mod = string(value, "/requires_mods/" + i);
                if (!MOD.matcher(mod).matches() || !mods.add(mod)) throw invalid("/requires_mods/" + i, "invalid or duplicate mod ID");
                i++;
            }
        }
        Map<String,Object> eligibility = object(required(root, "eligibility", ""), "/eligibility", "environments", "ranges", "predicates");
        Set<Environment> environments = EnumSet.noneOf(Environment.class);
        int index = 0;
        for (Object value : array(required(eligibility, "environments", "/eligibility"), "/eligibility/environments")) {
            String pointer = "/eligibility/environments/" + index++;
            Environment environment;
            try { environment = Environment.valueOf(string(value, pointer)); }
            catch (IllegalArgumentException e) { throw invalid(pointer, "unknown environment"); }
            if (!environments.add(environment)) throw invalid(pointer, "duplicate environment");
        }
        if (environments.isEmpty()) throw invalid("/eligibility/environments", "empty environments");
        Map<Field,Range> ranges = new EnumMap<>(Field.class);
        if (eligibility.containsKey("ranges")) {
            Map<String,Object> raw = object(eligibility.get("ranges"), "/eligibility/ranges", Arrays.stream(Field.values()).map(Enum::name).toArray(String[]::new));
            for (Map.Entry<String,Object> item : raw.entrySet()) {
                Field field = Field.valueOf(item.getKey());
                String p = "/eligibility/ranges/" + field;
                Map<String,Object> range = object(item.getValue(), p, "min", "max", "min_inclusive", "max_inclusive");
                if (!range.containsKey("min") && !range.containsKey("max")) throw invalid(p, "range requires a bound");
                if (range.containsKey("min_inclusive") && !range.containsKey("min") || range.containsKey("max_inclusive") && !range.containsKey("max")) throw invalid(p, "inclusive flag without bound");
                Double min = range.containsKey("min") ? number(range.get("min"), p + "/min") : null;
                Double max = range.containsKey("max") ? number(range.get("max"), p + "/max") : null;
                for (Double bound : Arrays.asList(min, max)) if (bound != null && (field.minimum != null && bound < field.minimum || field.maximum != null && bound > field.maximum)) throw invalid(p, "bound outside field domain");
                boolean lo = !range.containsKey("min_inclusive") || bool(range.get("min_inclusive"), p + "/min_inclusive");
                boolean hi = !range.containsKey("max_inclusive") || bool(range.get("max_inclusive"), p + "/max_inclusive");
                if (min != null && max != null && (min > max || min.doubleValue() == max.doubleValue() && (!lo || !hi))) throw invalid(p, "inverted or empty range");
                ranges.put(field, new Range(min, max, lo, hi));
            }
        }
        Map<Predicate,Boolean> predicates = new EnumMap<>(Predicate.class);
        if (eligibility.containsKey("predicates")) {
            Map<String,Object> raw = object(eligibility.get("predicates"), "/eligibility/predicates", Arrays.stream(Predicate.values()).map(Enum::name).toArray(String[]::new));
            raw.forEach((key,value) -> predicates.put(Predicate.valueOf(key), bool(value, "/eligibility/predicates/" + key)));
        }
        List<Predicate> trees = List.of(Predicate.trees_none, Predicate.trees_sparse, Predicate.trees_forest, Predicate.trees_dense, Predicate.trees_rainforest);
        if (trees.stream().filter(p -> Boolean.TRUE.equals(predicates.get(p))).count() > 1 || trees.stream().allMatch(p -> Boolean.FALSE.equals(predicates.get(p)))) throw invalid("/eligibility/predicates", "contradictory tree flags");
        if (Boolean.TRUE.equals(predicates.get(Predicate.slope_medium)) && Boolean.TRUE.equals(predicates.get(Predicate.slope_bare))) throw invalid("/eligibility/predicates", "contradictory slope flags");
        Boolean barren = predicates.get(Predicate.barren), dry = predicates.get(Predicate.too_arid), cold = predicates.get(Predicate.too_cold);
        if (Boolean.FALSE.equals(barren) && (Boolean.TRUE.equals(dry) || Boolean.TRUE.equals(cold)) || Boolean.TRUE.equals(barren) && Boolean.FALSE.equals(dry) && Boolean.FALSE.equals(cold)) throw invalid("/eligibility/predicates", "contradictory barren flags");
        if (environments.equals(Set.of(Environment.coast)) && Boolean.FALSE.equals(predicates.get(Predicate.coast))) throw invalid("/eligibility", "coast contradiction");
        Map<String,Object> selection = root.containsKey("selection") ? object(root.get("selection"), "/selection", "weight", "priority", "fallback") : Map.of();
        double weight = selection.containsKey("weight") ? number(selection.get("weight"), "/selection/weight") : 1;
        if (weight < 0) throw invalid("/selection/weight", "negative weight");
        int priority = 0;
        if (selection.containsKey("priority")) {
            Object value = selection.get("priority");
            if (!(value instanceof BigDecimal decimal)) throw invalid("/selection/priority", "expected signed integer");
            try { priority = decimal.intValueExact(); } catch (ArithmeticException e) { throw invalid("/selection/priority", "expected signed 32-bit integer"); }
        }
        boolean fallback = selection.containsKey("fallback") && bool(selection.get("fallback"), "/selection/fallback");
        return new Entry(id, biome, mods, environments, ranges, predicates, new Selection(weight, priority, fallback));
    }
}
