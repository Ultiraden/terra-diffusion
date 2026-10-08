package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;

/** Immutable v1 definitions. No TD derivation, biome policy or spatial selector lives here. */
public final class BiomeCatalog {
    private BiomeCatalog() {}
    public enum Environment { land, river, lake, coast, ocean }
    public enum Field {
        temperature_c(null, null), precipitation_mm(0d, null), temperature_seasonality(null, null),
        precipitation_cv(null, null), classification_elevation_m(null, null), classification_slope(0d, null),
        aridity(0d, null), tree_moisture(0d, null), effective_tree_moisture(0d, null),
        growing_season_days(0d, 365d), summer_max_c(null, null), physical_bathymetry_m(0d, null), water_depth_blocks(0d, null);
        final Double minimum, maximum;
        Field(Double minimum, Double maximum) { this.minimum = minimum; this.maximum = maximum; }
    }
    public enum Predicate { has_snow, trees_none, trees_sparse, trees_forest, trees_dense, trees_rainforest,
        barren, too_arid, too_cold, slope_medium, slope_bare, coast, submerged }
    public record Range(Double min, Double max, boolean minInclusive, boolean maxInclusive) {
        /** Missing future inputs and nonfinite samples never match; callers must not substitute zero. */
        public boolean matches(OptionalDouble value) {
            if (value.isEmpty() || !Double.isFinite(value.getAsDouble())) return false;
            double v = value.getAsDouble();
            return (min == null || (minInclusive ? v >= min : v > min))
                && (max == null || (maxInclusive ? v <= max : v < max));
        }
    }
    public record Selection(double weight, int priority, boolean fallback) {}
    public record Entry(String id, String biome, Set<String> requiredMods, Set<Environment> environments,
                        Map<Field,Range> ranges, Map<Predicate,Boolean> predicates, Selection selection) {
        public Entry {
            requiredMods = Set.copyOf(requiredMods);
            environments = Set.copyOf(environments);
            ranges = Map.copyOf(ranges);
            predicates = Map.copyOf(predicates);
        }
        public boolean predicateMatches(Predicate predicate, Optional<Boolean> value) {
            Boolean required = predicates.get(predicate);
            return required == null || value.filter(required::equals).isPresent();
        }
    }
    public record Candidate<B>(Entry definition, B biome) {}
    public record Snapshot<B>(boolean active, List<Candidate<B>> entries) {
        public Snapshot { entries = List.copyOf(entries); }
        public Optional<Candidate<B>> byId(String id) {
            return entries.stream().filter(c -> c.definition.id.equals(id)).findFirst();
        }
        public List<Candidate<B>> byEnvironment(Environment environment) {
            return entries.stream().filter(c -> c.definition.environments.contains(environment)).toList();
        }
    }
}
