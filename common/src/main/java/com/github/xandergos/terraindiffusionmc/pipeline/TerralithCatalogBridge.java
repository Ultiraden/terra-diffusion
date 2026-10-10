package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog;
import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogFilter;
import java.util.*;
import static com.github.xandergos.terraindiffusionmc.pipeline.TerralithBiomeIds.*;

/** Transitional ordered/spatial policy. Every environmental admission is a
 * generic catalog gate; these rule numbers bind the accepted #3 inventory.
 * One instance belongs to one tile/classification call, never shared workers. */
public final class TerralithCatalogBridge {
    public static final short NONE = 0;
    public enum NoMatch { NONE, POLICY_NONE, SELECTED_MAPPING_UNAVAILABLE }
    private enum Failure {
        MISSING("missing"), TARGET_MISMATCH("target-mismatch"), ZERO_WEIGHT("zero-weight"), HARD_INELIGIBLE("hard-ineligible");
        final String label;
        Failure(String label) { this.label=label; }
    }
    public record Diagnostics(long noMatch, long warmRiverNoMatch, long selectedMappingUnavailable,
        List<String> bindings, List<String> unavailableInputs, Map<String,Long> selectedFailures) {
        public Diagnostics {
            bindings=List.copyOf(bindings); unavailableInputs=List.copyOf(unavailableInputs);
            selectedFailures=Collections.unmodifiableMap(new TreeMap<>(selectedFailures));
        }
    }
    private static final FastNoiseLite VARIANT_BROAD = noise(0x7A1E, 1f / 3000f, 2);
    private static final FastNoiseLite VARIANT_LOCAL = noise(0x5C0D, 1f / 1200f, 2);
    private static final FastNoiseLite SPECIAL = noise(0x9E37, 1f / 1800f, 3);
    private static final FastNoiseLite VOLCANIC = noise(0x1F55, 1f / 5000f, 2);
    private static final FastNoiseLite SHOWPIECE_PICK = noise(0x2B7F, 1f / 14000f, 2);
    private static final float Q_TOP_10 = 0.632f, Q_TOP_20 = 0.598f, Q_TOP_25 = 0.579f;
    private static final float Q_TOP_33 = 0.547f, Q_MEDIAN = 0.500f, Q_BOT_33 = 0.453f, Q_BOT_25 = 0.421f;
    private static FastNoiseLite noise(int seed, float frequency, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFrequency(frequency); n.SetFractalOctaves(octaves);
        n.SetFractalLacunarity(2f); n.SetFractalGain(0.5f);
        return n;
    }
    private static float v(FastNoiseLite n, TerrainSample s) { return (n.GetNoise(s.worldX, s.worldZ) + 1f) * 0.5f; }
    private static boolean special(TerrainSample s) { return v(SPECIAL, s) > Q_TOP_10; }
    private static boolean volcanic(TerrainSample s) { return v(VOLCANIC, s) > 0.702f; }

    private final List<List<BiomeCatalogFilter.Compiled>> rules;
    private final List<String> bindingDiagnostics;
    private final List<BiomeCatalog.Entry> definitions;
    private final Set<String> unavailableInputs=new TreeSet<>();
    private final long[][] selectedFailures=new long[137][Failure.values().length];
    private static final BiomeCatalog.Field[] NUMERIC_FIELDS=BiomeCatalog.Field.values();
    private boolean inputsDiagnosed;
    private long diagnosedNonfiniteFields;
    private final byte[] admitted = new byte[137];
    private SampleCatalogContext context;
    private boolean selectedMappingUnavailable;
    private NoMatch lastNoMatch = NoMatch.NONE;
    private long noMatch, warmRiverNoMatch, unavailableMapping;
    public TerralithCatalogBridge(BiomeCatalog.Snapshot<?> snapshot) {
        if (!snapshot.active()) throw new IllegalArgumentException("inactive catalog uses explicit legacy classification");
        Map<String,BiomeCatalog.Entry> entries = new TreeMap<>();
        snapshot.entries().forEach(c -> entries.put(c.definition().id(), c.definition()));
        definitions=List.copyOf(entries.values());
        List<List<BiomeCatalogFilter.Compiled>> groups = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        groups.add(List.of());
        for (int rule = 1; rule < TerralithCatalogBindings.RESOURCES.length; rule++) {
            List<BiomeCatalogFilter.Compiled> group = new ArrayList<>();
            for (String id : TerralithCatalogBindings.RESOURCES[rule]) {
                BiomeCatalog.Entry entry = entries.get(id);
                if (entry == null) diagnostics.add(id + ": missing legacy mapping");
                else {
                    group.add(new BiomeCatalogFilter.Compiled(entry));
                    if (!entry.biome().equals(TerralithCatalogBindings.BIOMES[rule]))
                        diagnostics.add(id + ": target mismatch; expected " + TerralithCatalogBindings.BIOMES[rule] + ", got " + entry.biome());
                }
            }
            groups.add(List.copyOf(group));
        }
        rules = List.copyOf(groups);
        bindingDiagnostics = diagnostics.stream().sorted().toList();
    }
    public List<String> bindingDiagnostics() { return bindingDiagnostics; }
    public long noMatchCount() { return noMatch; }
    public long warmRiverNoMatchCount() { return warmRiverNoMatch; }
    public long unavailableMappingCount() { return unavailableMapping; }
    public NoMatch lastNoMatch() { return lastNoMatch; }
    public Diagnostics diagnostics() {
        Map<String,Long> failures=new TreeMap<>();
        for (int rule=1;rule<selectedFailures.length;rule++) for (Failure reason:Failure.values()) if (selectedFailures[rule][reason.ordinal()]>0) {
            String first=TerralithCatalogBindings.RESOURCES[rule][0];
            String group=first.substring(0,first.lastIndexOf('-'));
            failures.put(group+" -> "+TerralithCatalogBindings.BIOMES[rule]+" ("+reason.label+")",selectedFailures[rule][reason.ordinal()]);
        }
        return new Diagnostics(noMatch,warmRiverNoMatch,unavailableMapping,bindingDiagnostics,List.copyOf(unavailableInputs),failures);
    }
    private void start(TerrainSample s, boolean wet) {
        context = SampleCatalogContext.from(s, wet); Arrays.fill(admitted, (byte)0);
        selectedMappingUnavailable = false;
        lastNoMatch = NoMatch.NONE;
        // Future inputs/states are always absent in this adapter. Supplied
        // numeric values can become nonfinite in a later column, so report
        // each newly affected entry instead of inspecting only the first one.
        // The small primitive mask avoids scanning all definitions per pixel.
        long nonfiniteFields=0;
        for (var field : NUMERIC_FIELDS) if (!Double.isFinite(context.number(field)))
            nonfiniteFields |= 1L << field.ordinal();
        if (!inputsDiagnosed || (nonfiniteFields & ~diagnosedNonfiniteFields) != 0) {
            for (var definition : definitions) for (String input : BiomeCatalogFilter.unavailable(definition,context))
                unavailableInputs.add(definition.id()+": unavailable/nonfinite "+input);
            diagnosedNonfiniteFields |= nonfiniteFields;
            inputsDiagnosed=true;
        }
    }
    private boolean gate(int rule) {
        if (admitted[rule] == 0) {
            admitted[rule] = 1;
            for (var entry : rules.get(rule)) if (entry.eligible(context)) { admitted[rule] = 2; break; }
        }
        return admitted[rule] == 2;
    }
    private boolean any(int a, int b) { return gate(a) || gate(b); }
    private boolean any(int a, int b, int c) { return gate(a) || gate(b) || gate(c); }
    private boolean any(int a, int b, int c, int d) { return gate(a) || gate(b) || gate(c) || gate(d); }
    private short choose(int rule) {
        boolean expectedTarget=false, eligibleExpectedTarget=false;
        for (var entry : rules.get(rule)) if (entry.definition().biome().equals(TerralithCatalogBindings.BIOMES[rule])) {
            expectedTarget=true;
            if (entry.eligible(context)) {
                eligibleExpectedTarget=true;
                if (entry.definition().selection().weight() > 0d) return TerralithCatalogBindings.TARGETS[rule];
            }
        }
        unavailableMapping++;
        selectedMappingUnavailable = true;
        Failure reason=rules.get(rule).isEmpty() ? Failure.MISSING : !expectedTarget ? Failure.TARGET_MISMATCH : eligibleExpectedTarget ? Failure.ZERO_WEIGHT : Failure.HARD_INELIGIBLE;
        selectedFailures[rule][reason.ordinal()]++;
        return NONE;
    }
    public short warmRiver(TerrainSample s) {
        start(s, true); short result = gate(4) ? choose(4) : NONE;
        if (result == NONE) {
            noMatch++; warmRiverNoMatch++;
            lastNoMatch = selectedMappingUnavailable ? NoMatch.SELECTED_MAPPING_UNAVAILABLE : NoMatch.POLICY_NONE;
        }
        return result;
    }
    public short pick(TerrainSample s) {
        start(s, false);
        short result = ordered(s);
        if (result == NONE) {
            noMatch++;
            lastNoMatch = selectedMappingUnavailable ? NoMatch.SELECTED_MAPPING_UNAVAILABLE : NoMatch.POLICY_NONE;
        }
        return result;
    }
    private short ordered(TerrainSample s) {
        if (context.environment() == BiomeCatalog.Environment.ocean) {
            if (gate(1)) return choose(1);
            return gate(2) ? choose(2) : NONE;
        }
        if (gate(3)) return choose(3);
        // Bare-cliff NONE is terminal, unlike plateau/showpiece NONE.
        if (s.slopeBare) return cliff(s);
        short result = plateau(s); if (result != NONE || selectedMappingUnavailable) return result;
        result = alpine(s); if (result != NONE || selectedMappingUnavailable) return result;
        result = montane(s); if (result != NONE || selectedMappingUnavailable) return result;
        result = highland(s); if (result != NONE || selectedMappingUnavailable) return result;
        result = upland(s); if (result != NONE || selectedMappingUnavailable) return result;
        return lowland(s);
    }
    private short cliff(TerrainSample s) {
        if (gate(5) && special(s)) return choose(5);
        if (gate(6)) return choose(6);
        if (gate(7)) return choose(7);
        if (gate(8)) return choose(8);
        if (any(9,10)) return special(s) ? choose(9) : choose(10);
        if (gate(11) && volcanic(s)) return choose(11);
        if (gate(12)) return choose(12);
        if (gate(13)) return choose(13);
        if (any(14,15,16)) {
            float n = v(VARIANT_LOCAL, s);
            return choose(n < Q_BOT_33 ? 14 : n < Q_TOP_33 ? 15 : 16);
        }
        return NONE;
    }
    private short plateau(TerrainSample s) {
        if (volcanic(s)) return NONE;
        if (gate(17)) return choose(17);
        if (any(18,19,20,21)) {
            if (special(s)) return choose(v(VARIANT_LOCAL,s) > Q_MEDIAN ? 18 : 19);
            return choose(v(VARIANT_BROAD,s) > Q_TOP_33 ? 20 : 21);
        }
        if (any(22,23)) return choose(special(s) ? 22 : 23);
        if (gate(24)) return choose(24);
        if (gate(25)) return choose(25);
        short show = showpiece(s,121); if (show != NONE || selectedMappingUnavailable) return show;
        if (gate(26)) return choose(26);
        if (gate(27)) return choose(27);
        if (any(28,29)) {
            // Existing climate flags choose spatial quantiles, not admission.
            float n = v(VARIANT_BROAD,s);
            return choose(s.cold || s.cool ? (n > Q_TOP_33 ? 28 : 29) : (n > Q_MEDIAN ? 29 : 28));
        }
        if (gate(30)) return choose(30);
        if (any(31,32,33)) {
            float n = v(VARIANT_BROAD,s);
            return choose(s.cold || s.cool ? (n > Q_MEDIAN ? 31 : 32) : (n > Q_TOP_33 ? 33 : 32));
        }
        return NONE;
    }
    private short alpine(TerrainSample s) {
        if (any(34,35,36) && volcanic(s)) {
            float n = v(VARIANT_LOCAL,s); return choose(n > Q_TOP_10 ? 34 : n > Q_TOP_33 ? 35 : 36);
        }
        if (gate(37)) return choose(37);
        if (any(38,39)) return choose(v(VARIANT_BROAD,s) > Q_TOP_33 ? 38 : 39);
        if (gate(40)) return choose(40);
        return gate(41) ? choose(41) : NONE;
    }
    private short montane(TerrainSample s) {
        if (any(42,43) && volcanic(s)) return choose(v(VARIANT_LOCAL,s) > Q_TOP_33 ? 42 : 43);
        if (gate(44) && special(s)) return choose(44);
        if (gate(45)) return choose(45);
        if (gate(46)) return choose(46);
        if (gate(47)) return choose(47);
        if (gate(48)) return choose(48);
        if (gate(49)) return choose(49);
        if (gate(50)) return choose(50);
        if (gate(51)) return choose(51);
        return gate(52) ? choose(52) : NONE;
    }
    private short highland(TerrainSample s) {
        if (gate(53) && volcanic(s)) return choose(53);
        if (gate(54)) return choose(54);
        if (gate(55)) return choose(55);
        if (gate(56)) return choose(56);
        if (any(57,58)) {
            if (special(s)) return choose(57);
            return choose(v(VARIANT_LOCAL,s) > Q_TOP_33 ? 58 : 59);
        }
        if (gate(59)) return choose(59);
        if (gate(60)) return choose(60);
        if (any(61,62)) return choose(special(s) ? 61 : 62);
        if (any(63,64)) return choose(special(s) ? 63 : 64);
        short show = showpiece(s,125); if (show != NONE || selectedMappingUnavailable) return show;
        if (gate(65)) return choose(65);
        if (gate(66)) return choose(66);
        // These flags preserve branch/variant identity when an override removes
        // one member; the chosen exact mapping still needs generic admission.
        if ((s.cold || s.cool) && !s.treesNone && any(68,69))
            return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 68 : 69);
        if (s.treesNone && any(67,70))
            return choose(s.cold || s.cool ? 67 : v(VARIANT_BROAD,s) > Q_MEDIAN ? 70 : 67);
        if (s.treesSparse && any(69,71)) return choose(71);
        return gate(69) ? choose(69) : NONE;
    }
    private short upland(TerrainSample s) {
        if (any(72,73)) return choose(special(s) ? 72 : 73);
        if (any(74,75)) return choose(v(VARIANT_LOCAL,s) > Q_TOP_20 ? 74 : 75);
        if (gate(76)) return choose(76);
        if (any(77,78) && special(s)) return choose(v(VARIANT_LOCAL,s) > Q_MEDIAN ? 77 : 78);
        if (gate(79)) return choose(79);
        if (gate(80)) return choose(80);
        if (gate(81)) return choose(81);
        if (gate(82)) return choose(82);
        if (any(83,84)) return choose(v(VARIANT_LOCAL,s) > Q_MEDIAN ? 83 : 84);
        if (gate(85)) return choose(85);
        if (any(86,87,88)) {
            if (special(s)) return choose(86);
            return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 87 : 88);
        }
        if (gate(89)) return choose(89);
        if (gate(90)) return choose(90);
        if (gate(91)) return choose(91);
        short show = showpiece(s,129); if (show != NONE || selectedMappingUnavailable) return show;
        if (any(92,93)) return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 92 : 93);
        if (any(94,95)) return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 94 : 95);
        if (any(96,97)) return choose(v(VARIANT_BROAD,s) > Q_TOP_20 ? 96 : 97);
        return NONE;
    }
    private short lowland(TerrainSample s) {
        if (gate(98)) return choose(98);
        if (gate(99)) return choose(99);
        if (any(100,101)) return choose(special(s) ? 100 : 101);
        if (any(102,103,104)) {
            if (special(s)) return choose(102);
            return choose(v(VARIANT_LOCAL,s) > Q_TOP_20 ? 103 : 104);
        }
        if (any(105,106) && special(s)) return choose(v(VARIANT_LOCAL,s) > Q_MEDIAN ? 105 : 106);
        if (gate(107)) return choose(107);
        if (gate(108)) return choose(108);
        if (gate(109)) return choose(109);
        if (gate(110)) return choose(110);
        if (gate(111)) return choose(111);
        if (any(112,113)) return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 112 : 113);
        if (gate(114)) return choose(114);
        short show = showpiece(s,133); if (show != NONE || selectedMappingUnavailable) return show;
        if (any(115,116)) return choose(v(VARIANT_BROAD,s) > Q_TOP_33 ? 115 : 116);
        if (any(117,118)) return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 117 : 118);
        if (any(119,120)) return choose(v(VARIANT_BROAD,s) > Q_MEDIAN ? 119 : 120);
        return NONE;
    }
    private short showpiece(TerrainSample s, int first) {
        if (v(SPECIAL,s) <= 0.665f || !any(first,first+1,first+2,first+3)) return NONE;
        float n = v(SHOWPIECE_PICK,s);
        return choose(n < Q_BOT_25 ? first : n < Q_MEDIAN ? first+1 : n < Q_TOP_25 ? first+2 : first+3);
    }
}
