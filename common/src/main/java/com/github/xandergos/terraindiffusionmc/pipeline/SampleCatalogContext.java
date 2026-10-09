package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogFilter;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Captures the already-derived sample after slope overrides; never derives
 * climate, snow, bathymetry, water depth or a new coast/lake identity. */
public record SampleCatalogContext(Environment environment, float temperature, float precipitation,
    float seasonality, float precipitationCv, float elevation, float slope, float aridity,
    float moisture, float effectiveMoisture, float growingSeason, float summerMax, int flags)
    implements BiomeCatalogFilter.Context {
    public static SampleCatalogContext from(TerrainSample s, boolean wet) {
        Environment environment = s.isOcean ? Environment.ocean : wet ? Environment.river : Environment.land;
        int flags = (s.hasSnow ? 1 : 0) | (s.treesNone ? 2 : 0) | (s.treesSparse ? 4 : 0)
            | (s.treesForest ? 8 : 0) | (s.treesDense ? 16 : 0) | (s.treesRainforest ? 32 : 0)
            | (s.barren ? 64 : 0) | (s.tooArid ? 128 : 0) | (s.tooCold ? 256 : 0)
            | (s.slopeMedium ? 512 : 0) | (s.slopeBare ? 1024 : 0);
        // Preserve both source f32 arithmetic stages before promotion by numeric().
        float summer = s.temp + 1.414f * s.tStd;
        return new SampleCatalogContext(environment, s.temp, s.precip, s.tStd, s.pCV, s.elev,
            s.slope, s.aridity, s.treeMoisture, s.effTreeMoisture, s.growingSeason, summer, flags);
    }
    public OptionalDouble numeric(Field field) {
        if (field == Field.physical_bathymetry_m || field == Field.water_depth_blocks) return OptionalDouble.empty();
        return OptionalDouble.of(number(field));
    }
    public double number(Field field) {
        return switch (field) {
            case temperature_c -> temperature;
            case precipitation_mm -> precipitation;
            case temperature_seasonality -> seasonality;
            case precipitation_cv -> precipitationCv;
            case classification_elevation_m -> elevation;
            case classification_slope -> slope;
            case aridity -> aridity;
            case tree_moisture -> moisture;
            case effective_tree_moisture -> effectiveMoisture;
            case growing_season_days -> growingSeason;
            case summer_max_c -> summerMax;
            case physical_bathymetry_m, water_depth_blocks -> Double.NaN;
        };
    }
    public Optional<Boolean> state(Predicate predicate) {
        if (predicate == Predicate.coast || predicate == Predicate.submerged) return Optional.empty();
        return Optional.of((flags & (1 << predicate.ordinal())) != 0);
    }
    public int stateCode(Predicate predicate) {
        return predicate == Predicate.coast || predicate == Predicate.submerged ? -1 : (flags >>> predicate.ordinal()) & 1;
    }
}
