package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogFilter;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Tile-confined primitive view for selection. Never retain in a trace/publication.
 * Derivation is completed by BiomeClassifier before update; no ecology is rederived. */
public final class MutableSampleCatalogContext implements BiomeCatalogFilter.Context {
    private TerrainSample sample;
    private boolean wet;
    public void update(TerrainSample sample,boolean wet){this.sample=Objects.requireNonNull(sample);this.wet=wet;}
    public Environment environment(){return sample.isOcean?Environment.ocean:wet?Environment.river:Environment.land;}
    public OptionalDouble numeric(Field field){return field==Field.physical_bathymetry_m||field==Field.water_depth_blocks?OptionalDouble.empty():OptionalDouble.of(number(field));}
    public double number(Field field){
        TerrainSample s=sample;
        return switch(field){
            case temperature_c -> s.temp;case precipitation_mm -> s.precip;case temperature_seasonality -> s.tStd;case precipitation_cv -> s.pCV;
            case classification_elevation_m -> s.elev;case classification_slope -> s.slope;case aridity -> s.aridity;
            case tree_moisture -> s.treeMoisture;case effective_tree_moisture -> s.effTreeMoisture;case growing_season_days -> s.growingSeason;
            case summer_max_c -> s.temp+1.414f*s.tStd;
            case physical_bathymetry_m,water_depth_blocks -> Double.NaN;
        };
    }
    public Optional<Boolean> state(Predicate p){int value=stateCode(p);return value<0?Optional.empty():Optional.of(value==1);}
    public int stateCode(Predicate p){
        TerrainSample s=sample;
        return switch(p){
            case has_snow -> s.hasSnow?1:0;case trees_none -> s.treesNone?1:0;case trees_sparse -> s.treesSparse?1:0;
            case trees_forest -> s.treesForest?1:0;case trees_dense -> s.treesDense?1:0;case trees_rainforest -> s.treesRainforest?1:0;
            case barren -> s.barren?1:0;case too_arid -> s.tooArid?1:0;case too_cold -> s.tooCold?1:0;
            case slope_medium -> s.slopeMedium?1:0;case slope_bare -> s.slopeBare?1:0;case coast,submerged -> -1;
        };
    }
}
