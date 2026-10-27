package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog;
import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalogFilter;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Developer observation only: no world, registry holder, inference or terrain cache references.
 * Reads never promote records. Normal successful canonical tiles alone publish observations. */
public final class BiomeDebugTrace {
    public static final String POSITIONS_PROPERTY = "terradiff.debug.biome.positions";
    public static final int MAX_POSITIONS = 64, MAX_RECORD_BYTES = 1024 * 1024, MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    public static final int PAGE_SIZE = 16;
    private static final Set<Position> POSITIONS = parsePositions(System.getProperty(POSITIONS_PROPERTY, ""));
    private static final Map<Position, Observation> RECORDS = new HashMap<>();
    private static long epoch;
    private static int retainedBytes;
    private BiomeDebugTrace() {}

    public record Position(int x, int z) {}
    public record TileKey(long seed, int scale, int i1, int j1, int i2, int j2) {}
    public record EntryResult(String id, String biome, boolean eligible, double weight, int priority,
                              boolean fallback, List<String> rejections) {
        public EntryResult { rejections = List.copyOf(rejections); }
        public String line() {
            return id + " -> " + biome + " eligible=" + eligible + " weight=" + weight
                + " priority=" + priority + " fallback=" + fallback
                + (rejections.isEmpty() ? "" : " rejected=" + String.join(",", rejections));
        }
    }
    /** The full 34-field derived sample, copied before the classifier reuses its mutable sample. */
    public record Sample(int worldX, int worldZ, float elev, float altM, float slope, float temp,
        float tStd, float precip, float pCV, float aridity, float treeMoisture, float growingSeason,
        float effTreeMoisture, float bareThreshold, boolean treesNone, boolean treesSparse,
        boolean treesForest, boolean treesDense, boolean treesRainforest, boolean barren,
        boolean tooArid, boolean tooCold, boolean slopeMedium, boolean slopeBare, boolean hasSnow,
        boolean isOcean, boolean mountains, boolean lowland, boolean frozen, boolean cold,
        boolean cool, boolean temperate, boolean warm, boolean hot) {
        public static Sample from(TerrainSample s) {
            return new Sample(s.worldX,s.worldZ,s.elev,s.altM,s.slope,s.temp,s.tStd,s.precip,s.pCV,
                s.aridity,s.treeMoisture,s.growingSeason,s.effTreeMoisture,s.bareThreshold,s.treesNone,
                s.treesSparse,s.treesForest,s.treesDense,s.treesRainforest,s.barren,s.tooArid,s.tooCold,
                s.slopeMedium,s.slopeBare,s.hasSnow,s.isOcean,s.mountains,s.lowland,s.frozen,s.cold,
                s.cool,s.temperate,s.warm,s.hot);
        }
    }
    public record GenericSelection(int paletteIndex,String paletteId,int spacingWorldBlocks,
        com.github.xandergos.terraindiffusionmc.catalog.CoherentBiomeSelector.Decision decision) {}
    public record Observation(TileKey tile, Position position, float pixelSizeM, String catalogId,
        boolean catalogActive, int catalogEntries, Sample sample, SampleCatalogContext context,
        boolean wet, String policy, String route, short surfaceId, String surfaceBiome,
        int snowDepth, String selectedEntry, String fallback, List<String> gates,
        List<EntryResult> entries, int eligibleCount, String unavailable, int bytes,GenericSelection genericSelection) {
        public Observation { gates = List.copyOf(gates); entries = List.copyOf(entries); }
    }

    /** Strict, quart-aligned block X/Z. No implicit snapping or command-time configuration. */
    public static Set<Position> parsePositions(String value) {
        if (value.isEmpty()) return Set.of();
        String[] parts = value.split(";", -1);
        if (parts.length > MAX_POSITIONS) throw new IllegalArgumentException(POSITIONS_PROPERTY + ": at most 64 positions");
        Set<Position> positions = new LinkedHashSet<>();
        for (String part : parts) {
            if (!part.matches("-?(0|[1-9][0-9]*),-?(0|[1-9][0-9]*)"))
                throw new IllegalArgumentException(POSITIONS_PROPERTY + ": expected quart-aligned block x,z pairs separated by ;");
            String[] xy = part.split(",");
            int x = Integer.parseInt(xy[0]), z = Integer.parseInt(xy[1]);
            if ((x & 3) != 0 || (z & 3) != 0) throw new IllegalArgumentException(POSITIONS_PROPERTY + ": coordinates must be multiples of 4");
            if (!positions.add(new Position(x,z))) throw new IllegalArgumentException(POSITIONS_PROPERTY + ": duplicate position");
        }
        return Collections.unmodifiableSet(positions);
    }
    public static boolean enabled() { return !POSITIONS.isEmpty(); }
    public static boolean configured(int x,int z) { return POSITIONS.contains(new Position(x,z)); }
    public static synchronized void clear() { RECORDS.clear(); retainedBytes = 0; epoch++; }
    public static synchronized Optional<Observation> read(Position position, TileKey expectedTile) {
        Observation value = RECORDS.get(position);
        return value != null && value.tile().equals(expectedTile) ? Optional.of(value) : Optional.empty();
    }
    public static synchronized int retainedBytes() { return retainedBytes; }
    public static synchronized int retainedRecords() { return RECORDS.size(); }

    /** Null means no matching configured point or noncanonical request; zero production work then. */
    public static synchronized Tile begin(TileKey key, int tileSize, BiomeCatalog.Snapshot<?> catalog) {
        if (!enabled() || key.i2()-key.i1()!=tileSize || key.j2()-key.j1()!=tileSize
            || Math.floorMod(key.i1(),tileSize)!=0 || Math.floorMod(key.j1(),tileSize)!=0) return null;
        Set<Position> points = new HashSet<>();
        for (Position p : POSITIONS) if (p.x()>=key.j1() && p.x()<key.j2() && p.z()>=key.i1() && p.z()<key.i2()) points.add(p);
        return points.isEmpty() ? null : new Tile(key,points,catalog,epoch);
    }
    public static synchronized void publish(Tile tile) {
        if (tile == null || tile.epoch != epoch) return;
        for (var item : tile.completed.entrySet()) {
            Observation old = RECORDS.remove(item.getKey());
            if (old != null) retainedBytes -= old.bytes();
            Observation record = item.getValue();
            if (retainedBytes + record.bytes() > MAX_TOTAL_BYTES - MAX_POSITIONS * 512)
                record = unavailable(record,"total trace budget exceeded");
            RECORDS.put(item.getKey(),record); retainedBytes += record.bytes();
        }
    }
    private static Observation unavailable(Observation r,String reason) {
        return new Observation(r.tile(),r.position(),r.pixelSizeM(),r.catalogId(),r.catalogActive(),
            r.catalogEntries(),null,null,r.wet(),r.policy(),r.route(),r.surfaceId(),r.surfaceBiome(),
            r.snowDepth(),null,r.fallback(),List.of(),List.of(),0,reason,512,r.genericSelection());
    }

    /** Scoped to one normal tile task. Retains the captured catalog only until the task completes. */
    public static final class Tile {
        private final TileKey key;
        private final Set<Position> points;
        private final BiomeCatalog.Snapshot<?> catalog;
        private final String catalogId;
        private final long epoch;
        private final Map<Position,Observation> completed = new HashMap<>();
        private int completedBytes;
        private Tile(TileKey key,Set<Position> points,BiomeCatalog.Snapshot<?> catalog,long epoch) {
            this.key=key; this.points=Set.copyOf(points); this.catalog=catalog; this.epoch=epoch;
            this.catalogId=digest(catalog);
        }
        public boolean watches(int x,int z) { return points.contains(new Position(x,z)); }
        public void missingClimate(float pixelSizeM) {
            for (Position p:points) completed.put(p,new Observation(key,p,pixelSizeM,catalogId,catalog.active(),
                catalog.entries().size(),null,null,false,"default","missing-climate",(short)1,"minecraft:plains",
                0,null,"missing climate defaults to plains",List.of(),List.of(),0,"precise climate unavailable",512,null));
        }
        public void capture(TerrainSample s, boolean wet, float pixelSizeM, String policy,String route,
            short id,int snowDepth, Selection selection,String fallback) {
            captureObserved(s,wet,pixelSizeM,policy,route,id,biomeName(id),snowDepth,selection,
                selection==null?null:selection.selectedEntry,fallback,null);
        }
        public void captureGeneric(TerrainSample s,boolean wet,float pixelSizeM,String route,int index,String biome,
                int snowDepth,String entry,String fallback,int spacing,String palette,
                com.github.xandergos.terraindiffusionmc.catalog.CoherentBiomeSelector.Decision decision){
            captureObserved(s,wet,pixelSizeM,"generic-cellular",route,(short)0,biome,snowDepth,null,entry,fallback,
                new GenericSelection(index,palette,spacing,decision));
        }
        private void captureObserved(TerrainSample s,boolean wet,float pixelSizeM,String policy,String route,
                short id,String biome,int snowDepth,Selection selection,String selectedEntry,String fallback,GenericSelection generic){
            Sample sample=Sample.from(s); SampleCatalogContext context=SampleCatalogContext.from(s,wet);
            List<EntryResult> entries=new ArrayList<>(); int eligible=0; long bytes=2048;
            if(generic!=null && selectedEntry!=null)bytes+=stringBytes(selectedEntry);
            String reason="";
            if (selection!=null && selection.budgetExceeded) reason="selection trace budget exceeded";
            if (selection!=null) for (String gate:selection.gates) bytes+=stringBytes(gate);
            // Snapshot definitions are already immutable; no holders enter an observation.
            // Sort only IDs/references, never an unbounded serialized payload.
            if (catalog.entries().size() > MAX_RECORD_BYTES / 256) reason="record trace budget exceeded";
            List<BiomeCatalog.Entry> definitions=!reason.isEmpty() ? List.of() : catalog.entries().stream().map(c->c.definition())
                .sorted(Comparator.comparing(BiomeCatalog.Entry::id)).toList();
            for (BiomeCatalog.Entry definition:definitions) {
                long entryStrings=stringBytes(definition.id())+stringBytes(definition.biome());
                if(bytes+256+entryStrings>MAX_RECORD_BYTES) { reason="record trace budget exceeded"; entries.clear(); eligible=0; break; }
                EntryResult entry=explain(definition,context);
                bytes+=256+entryStrings;
                for(String rejection:entry.rejections()) bytes+=stringBytes(rejection);
                if(bytes>MAX_RECORD_BYTES) { reason="record trace budget exceeded"; entries.clear(); eligible=0; break; }
                entries.add(entry); if(entry.eligible()) eligible++;
            }
            Observation result=new Observation(key,new Position(s.worldX,s.worldZ),pixelSizeM,catalogId,catalog.active(),
                catalog.entries().size(),sample,context,wet,policy,route,id,biome,snowDepth,
                selectedEntry,fallback,selection==null ? List.of() : selection.gates,
                entries,eligible,reason,(int)Math.min(bytes,MAX_RECORD_BYTES),generic);
            if(reason.isEmpty() && completedBytes+result.bytes()>MAX_TOTAL_BYTES - MAX_POSITIONS*512)
                reason="tile trace budget exceeded";
            result=reason.isEmpty() ? result : unavailable(result,reason);
            completed.put(result.position(),result); completedBytes+=result.bytes();
        }
    }
    /** Only actual bridge gate/choose calls populate this bounded per-column collector. */
    public static final class Selection {
        private static final int MAX_SELECTION_BYTES=64*1024;
        private final List<String> gates=new ArrayList<>();
        private String selectedEntry;
        private long bytes;
        private boolean budgetExceeded;
        private boolean add(String prefix,String value) {
            long size=stringBytes(prefix)+stringBytes(value);
            if(bytes+size>MAX_SELECTION_BYTES) { budgetExceeded=true; return false; }
            bytes+=size; gates.add(prefix+value); return true;
        }
        public void gate(int rule,boolean eligible) { add("rule="+rule+" gate=",Boolean.toString(eligible)); }
        public void chosen(int rule,String entry) { if(add("rule="+rule+" selected=",entry)) selectedEntry=entry; }
        public void failed(int rule,String reason) { add("rule="+rule+" selected-mapping-unavailable=",reason); }
    }
    public static EntryResult explain(BiomeCatalog.Entry entry,BiomeCatalogFilter.Context context) {
        List<String> reasons=new ArrayList<>();
        if(!entry.environments().contains(context.environment())) reasons.add("environment:"+context.environment());
        for(var field:BiomeCatalog.Field.values()) {
            var range=entry.ranges().get(field); if(range==null) continue;
            var value=context.numeric(field);
            if(value.isEmpty()) { reasons.add(field+":unavailable"); continue; }
            double number=value.getAsDouble();
            if(!Double.isFinite(number)) { reasons.add(field+":nonfinite"); continue; }
            if(range.min()!=null && (range.minInclusive() ? number<range.min() : number<=range.min()))
                reasons.add(field+(number==range.min() ? ":excluded-min" : ":below-min"));
            if(range.max()!=null && (range.maxInclusive() ? number>range.max() : number>=range.max()))
                reasons.add(field+(number==range.max() ? ":excluded-max" : ":above-max"));
        }
        for(var predicate:BiomeCatalog.Predicate.values()) {
            Boolean expected=entry.predicates().get(predicate); if(expected==null) continue;
            int actual=context.stateCode(predicate);
            if(actual<0) reasons.add(predicate+":unavailable");
            else if((actual==1)!=expected) reasons.add(predicate+":requires-"+expected);
        }
        return new EntryResult(entry.id(),entry.biome(),reasons.isEmpty(),entry.selection().weight(),
            entry.selection().priority(),entry.selection().fallback(),reasons);
    }
    private static long stringBytes(String value) { return 40L+2L*value.length(); }
    static String digest(BiomeCatalog.Snapshot<?> catalog) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            DigestText text=new DigestText(digest);
            text.update(Boolean.toString(catalog.active()));
            // Loader snapshots already use winning resource ID order. Preserve the exact captured
            // snapshot order, without constructing another catalog-sized array solely for hashing.
            for(var candidate:catalog.entries()) {
                var e=candidate.definition();
                text.update(e.id()); text.update(e.biome());
                e.requiredMods().stream().sorted().forEach(text::update);
                for(var env:BiomeCatalog.Environment.values()) text.update(env+":"+e.environments().contains(env));
                for(var field:BiomeCatalog.Field.values()) text.update(field+":"+e.ranges().get(field));
                for(var predicate:BiomeCatalog.Predicate.values()) text.update(predicate+":"+e.predicates().get(predicate));
                text.update(e.selection().toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    /** Fixed encoding scratch; never value.getBytes() on potentially large valid resource IDs. */
    private static final class DigestText {
        private final MessageDigest digest;
        private final ByteBuffer scratch=ByteBuffer.allocate(4096);
        private final java.nio.charset.CharsetEncoder encoder=StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        DigestText(MessageDigest digest) { this.digest=digest; }
        void update(String value) {
            encoder.reset(); CharBuffer input=CharBuffer.wrap(value);
            boolean done;
            do {
                scratch.clear(); done=!encoder.encode(input,scratch,true).isOverflow();
                scratch.flip(); digest.update(scratch);
            } while(!done);
            scratch.clear(); encoder.flush(scratch); scratch.flip(); digest.update(scratch); digest.update((byte)0);
        }
    }
    /** Names mirror the existing immutable source palette, without triggering its lazy holder resolution. */
    public static String biomeName(short id) {
        String terralith=TerralithBiomeIds.paths().get(id);
        if(terralith!=null) return "terralith:"+terralith;
        return switch(id) {
            case 1 -> "minecraft:plains"; case 7 -> "minecraft:river"; case 11 -> "minecraft:frozen_river";
            case 3 -> "minecraft:snowy_plains"; case 5 -> "minecraft:desert"; case 6 -> "minecraft:swamp";
            case 8 -> "minecraft:forest"; case 15 -> "minecraft:taiga"; case 16 -> "minecraft:snowy_taiga";
            case 17 -> "minecraft:savanna"; case 19 -> "minecraft:windswept_hills"; case 23 -> "minecraft:jungle";
            case 26 -> "minecraft:badlands"; case 29 -> "minecraft:meadow"; case 31 -> "minecraft:grove";
            case 32 -> "minecraft:snowy_slopes"; case 33 -> "minecraft:frozen_peaks"; case 35 -> "minecraft:stony_peaks";
            case 41 -> "minecraft:warm_ocean"; case 44 -> "minecraft:ocean"; case 46 -> "minecraft:cold_ocean";
            case 48 -> "minecraft:frozen_ocean"; case 108 -> "terrain-diffusion-mc:forest_sparse";
            case 115 -> "terrain-diffusion-mc:taiga_sparse"; case 116 -> "terrain-diffusion-mc:snowy_taiga_sparse";
            default -> "unknown-short-id:"+id;
        };
    }
}
