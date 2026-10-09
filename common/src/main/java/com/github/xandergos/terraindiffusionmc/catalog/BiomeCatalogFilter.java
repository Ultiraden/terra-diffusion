package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Generic hard admission only. Registry/mod resolution belongs to the loader;
 * weights, priorities, fallbacks and spatial selection belong to policy. */
public final class BiomeCatalogFilter {
    private BiomeCatalogFilter() {}
    public interface Context {
        Environment environment();
        OptionalDouble numeric(Field field);
        Optional<Boolean> state(Predicate predicate);
        /** Primitive access permits existing typed adapters to avoid allocation. */
        default double number(Field field) { return numeric(field).orElse(Double.NaN); }
        default int stateCode(Predicate predicate) { return state(predicate).map(v -> v ? 1 : 0).orElse(-1); }
    }
    /** Immutable context for callers that do not have an existing typed sample. */
    public record Values(Environment environment, Map<Field,Double> numbers,
                         Map<Predicate,Boolean> states) implements Context {
        public Values {
            Objects.requireNonNull(environment);
            numbers = Map.copyOf(numbers);
            states = Map.copyOf(states);
        }
        public OptionalDouble numeric(Field field) {
            Double value = numbers.get(field);
            return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
        }
        public Optional<Boolean> state(Predicate predicate) { return Optional.ofNullable(states.get(predicate)); }
    }
    public static boolean eligible(Entry entry, Context context) {
        if (!entry.environments().contains(context.environment())) return false;
        for (var constraint : entry.ranges().entrySet())
            if (!constraint.getValue().matches(context.numeric(constraint.getKey()))) return false;
        for (var constraint : entry.predicates().entrySet())
            if (!entry.predicateMatches(constraint.getKey(), context.state(constraint.getKey()))) return false;
        return true;
    }
    /** Immutable compiled form of the same AND contract, without per-pixel maps
     * or Optional allocation. NaN means unavailable; every constrained numeric
     * input must still be finite. No weights or policy occur in this evaluator. */
    public static final class Compiled {
        private record Numeric(Field field, Double min, Double max, boolean minInclusive, boolean maxInclusive) {}
        private record State(Predicate predicate, int required) {}
        private final Entry definition;
        private final Set<Environment> environments;
        private final Numeric[] numbers;
        private final State[] states;
        public Compiled(Entry entry) {
            definition = entry; environments = entry.environments();
            List<Numeric> n = new ArrayList<>();
            for (Field f : Field.values()) {
                Range r = entry.ranges().get(f);
                if (r != null) n.add(new Numeric(f,r.min(),r.max(),r.minInclusive(),r.maxInclusive()));
            }
            numbers = n.toArray(Numeric[]::new);
            List<State> p = new ArrayList<>();
            for (Predicate f : Predicate.values()) {
                Boolean required = entry.predicates().get(f);
                if (required != null) p.add(new State(f,required ? 1 : 0));
            }
            states = p.toArray(State[]::new);
        }
        public Entry definition() { return definition; }
        public boolean eligible(Context context) {
            if (!environments.contains(context.environment())) return false;
            for (Numeric constraint : numbers) {
                double value = context.number(constraint.field);
                if (!Double.isFinite(value)) return false;
                if (constraint.min != null && (constraint.minInclusive ? value < constraint.min : value <= constraint.min)) return false;
                if (constraint.max != null && (constraint.maxInclusive ? value > constraint.max : value >= constraint.max)) return false;
            }
            for (State constraint : states) if (context.stateCode(constraint.predicate) != constraint.required) return false;
            return true;
        }
    }
    public static <B> List<Candidate<B>> filter(Snapshot<B> snapshot, Context context) {
        if (!snapshot.active()) return List.of();
        return snapshot.entries().stream().filter(c -> eligible(c.definition(), context))
            .sorted(Comparator.comparing(c -> c.definition().id())).toList();
    }
    /** Stable diagnostic keys; unconstrained future inputs produce no diagnostic. */
    public static List<String> unavailable(Entry entry, Context context) {
        List<String> missing = new ArrayList<>();
        for (Field field : Field.values()) if (entry.ranges().containsKey(field)) {
            OptionalDouble value = context.numeric(field);
            if (value.isEmpty() || !Double.isFinite(value.getAsDouble())) missing.add(field.name());
        }
        for (Predicate state : Predicate.values())
            if (entry.predicates().containsKey(state) && context.state(state).isEmpty()) missing.add(state.name());
        return List.copyOf(missing);
    }
}
