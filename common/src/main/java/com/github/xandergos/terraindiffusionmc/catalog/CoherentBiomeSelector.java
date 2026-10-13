package com.github.xandergos.terraindiffusionmc.catalog;

import java.util.*;
import static com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog.*;

/** Pure generic selection. No registry lookup, world lifecycle, cache mutation or TD derivation.
 * Geometry and sampling constants define cellular-v1 and must not silently change.
 * Caller owns one bounded Scratch per tile; returned candidates are snapshot objects. */
public final class CoherentBiomeSelector<B> {
    public static final String ALGORITHM = "cellular-v1";
    public static final int DEFAULT_SPACING = 1024;
    public static final int MIN_SPACING = 16, MAX_SPACING = 65536;
    public static final long WORLD_LIMIT = 30_000_000L;
    public static final int MAX_SCRATCH_SITES = 65536;
    public static final int MAX_CANONICAL_TILE_SIZE = 65536;
    private static final double QUANTUM = 0x1.0p-53;
    private static final long X_DOMAIN = 0x6a09e667f3bcc909L, Z_DOMAIN = 0xbb67ae8584caa73bL;
    private static final long JITTER_X = 0x3c6ef372fe94f82bL, JITTER_Z = 0xa54ff53a5f1d36f1L;
    private static final long LABEL = 0x510e527fade682d1L;

    public record Config(int spacingBlocks) {
        public Config {
            if (spacingBlocks < MIN_SPACING || spacingBlocks > MAX_SPACING)
                throw new IllegalArgumentException("spacingBlocks must be in [16,65536]");
        }
        public static Config defaults() { return new Config(DEFAULT_SPACING); }
    }
    public enum NoMatch { NONE, INACTIVE_CATALOG, NO_ELIGIBLE_POSITIVE_CANDIDATE }
    public record Site(long cellX, long cellZ, double x, double z, double quantile) {}
    public record Decision(String algorithm, Site site, int priority, boolean fallback,
                           int retainedCandidates, double totalWeight, int collapsedPositiveIntervals,
                           int belowQuantileResolution, int weightScaleExponent, NoMatch noMatch) {}
    private record Eligibility(Set<Environment> environments, Map<Field,Range> ranges,
                               Map<Predicate,Boolean> predicates) {}
    private record Tier(boolean fallback, int priority, int[] entries) {}
    private final boolean active;
    private final long worldSeed;
    private final Config config;
    private final List<Candidate<B>> candidates;
    private final BiomeCatalogFilter.Compiled[] programs;
    private final int[] programOf;
    private final double[] weights;
    private final Tier[] tiers;

    public CoherentBiomeSelector(Snapshot<B> snapshot, long worldSeed, Config config) {
        this.active = snapshot.active(); this.worldSeed = worldSeed; this.config = Objects.requireNonNull(config);
        List<Candidate<B>> ordered = new ArrayList<>(snapshot.entries());
        ordered.sort(Comparator.comparing(c -> c.definition().id()));
        this.candidates = List.copyOf(ordered);
        this.programOf = new int[ordered.size()]; this.weights = new double[ordered.size()];
        Map<Eligibility,Integer> shared = new HashMap<>();
        List<BiomeCatalogFilter.Compiled> compiled = new ArrayList<>();
        // Normal before fallback; larger signed priority first. Never subtract priorities.
        Comparator<Tier> tierOrder = Comparator.comparing(Tier::fallback)
            .thenComparing(Comparator.comparingInt(Tier::priority).reversed());
        Map<Tier,List<Integer>> groups = new TreeMap<>(tierOrder);
        String previous = null; double sum = 0;
        for (int i=0;i<ordered.size();i++) {
            Entry e = ordered.get(i).definition();
            if (e.id().equals(previous)) throw new IllegalArgumentException("duplicate entry ID: "+e.id());
            previous=e.id(); double w=e.selection().weight();
            if (!Double.isFinite(w) || w<0) throw new IllegalArgumentException("invalid weight: "+e.id());
            sum+=w; if (!Double.isFinite(sum)) throw new IllegalArgumentException("nonfinite ASCII weight sum");
            weights[i]=w;
            Eligibility key=new Eligibility(e.environments(),e.ranges(),e.predicates());
            Integer index=shared.get(key);
            if (index==null) { index=compiled.size(); shared.put(key,index); compiled.add(new BiomeCatalogFilter.Compiled(e)); }
            programOf[i]=index;
            if (w>0) groups.computeIfAbsent(new Tier(e.selection().fallback(),e.selection().priority(),null),ignored->new ArrayList<>()).add(i);
        }
        programs=compiled.toArray(BiomeCatalogFilter.Compiled[]::new);
        tiers=groups.entrySet().stream().map(g->new Tier(g.getKey().fallback(),g.getKey().priority(),g.getValue().stream().mapToInt(Integer::intValue).toArray())).toArray(Tier[]::new);
    }

    public int candidateCount() { return candidates.size(); }
    public int sharedProgramCount() { return programs.length; }
    public Config config() { return config; }
    /** Debug/test only; production select never creates an eligible list. */
    public List<Candidate<B>> eligible(BiomeCatalogFilter.Context context) {
        if (!active) return List.of();
        List<Candidate<B>> out=new ArrayList<>();
        for (int i=0;i<candidates.size();i++) if (programs[programOf[i]].eligible(context)) out.add(candidates.get(i));
        return List.copyOf(out);
    }

    /** Fixed rectangular scratch. Bounds are block positions, upper bounds exclusive.
     * Requests needing more sites must split their scratch, not create an unbounded map. */
    public Scratch scratch(long minX,long minZ,long maxXExclusive,long maxZExclusive) {
        return new Scratch(minX,minZ,maxXExclusive,maxZExclusive,0);
    }
    /** Complete internal tile at the world edge. Actual valid positions retain identical geometry.
     * Only aligned tiles intersecting the valid domain qualify; public point scratch stays strict.
     * The per-tile spill is at most size-1 and the existing site memory budget still applies. */
    public Scratch canonicalScratch(long tileX,long tileZ,int size) {
        if(size<=0||size>MAX_CANONICAL_TILE_SIZE||(size&(size-1))!=0
                ||Math.floorMod(tileX,size)!=0||Math.floorMod(tileZ,size)!=0)
            throw new IllegalArgumentException("invalid bounded canonical tile");
        long spill=size-1L;
        if(tileX < -WORLD_LIMIT-spill||tileX>WORLD_LIMIT||tileZ < -WORLD_LIMIT-spill||tileZ>WORLD_LIMIT
                ||tileX+size<=-WORLD_LIMIT||tileZ+size<=-WORLD_LIMIT)
            throw new IllegalArgumentException("canonical tile does not intersect valid world positions");
        return new Scratch(tileX,tileZ,tileX+size,tileZ+size,spill);
    }
    public final class Scratch {
        private final CoherentBiomeSelector<B> owner=CoherentBiomeSelector.this;
        private final long minX,minZ,maxX,maxZ,firstCellX,firstCellZ;
        private final int nx,nz;
        private final double[] siteX,siteZ,labels;
        private final int[] evaluatedEpoch,retained;
        private final byte[] admitted;
        private int epoch,selectedSite,retainedCount,priority,collapsed,belowResolution,weightScaleExponent;
        private boolean fallback;
        private double total;
        private NoMatch noMatch=NoMatch.NO_ELIGIBLE_POSITIVE_CANDIDATE;
        private long programEvaluations,distanceEvaluations;
        private Scratch(long minX,long minZ,long maxX,long maxZ,long spill) {
            if (minX < -WORLD_LIMIT-spill || minZ < -WORLD_LIMIT-spill || maxX > WORLD_LIMIT+1+spill || maxZ > WORLD_LIMIT+1+spill || minX>=maxX || minZ>=maxZ)
                throw new IllegalArgumentException("invalid supported world rectangle");
            this.minX=minX;this.minZ=minZ;this.maxX=maxX;this.maxZ=maxZ;
            int h=config.spacingBlocks();
            firstCellX=Math.floorDiv(minX,h)-1;firstCellZ=Math.floorDiv(minZ,h)-1;
            long width=Math.floorDiv(maxX-1,h)-firstCellX+2, height=Math.floorDiv(maxZ-1,h)-firstCellZ+2;
            if (width>MAX_SCRATCH_SITES || height>MAX_SCRATCH_SITES || width*height>MAX_SCRATCH_SITES)
                throw new IllegalArgumentException("scratch exceeds fixed site budget; split the rectangle");
            nx=(int)width;nz=(int)height;
            siteX=new double[nx*nz];siteZ=new double[nx*nz];labels=new double[nx*nz];
            for (int z=0;z<nz;z++) for (int x=0;x<nx;x++) {
                int index=z*nx+x; long cx=firstCellX+x,cz=firstCellZ+z;
                long base=base(worldSeed,cx,cz);
                siteX[index]=position(cx,h,uniform(mix64(base^JITTER_X)));
                siteZ[index]=position(cz,h,uniform(mix64(base^JITTER_Z)));
                labels[index]=uniform(mix64(base^LABEL));
            }
            evaluatedEpoch=new int[programs.length];admitted=new byte[programs.length];retained=new int[candidates.size()];
        }
        public int siteCount() { return nx*nz; }
        public long programEvaluations() { return programEvaluations; }
        public long distanceEvaluations() { return distanceEvaluations; }
        public NoMatch noMatch() { return noMatch; }
        public int retainedCount() { return retainedCount; }
        public int collapsedPositiveIntervals() { return collapsed; }
        public int belowQuantileResolution() { return belowResolution; }
        public int weightScaleExponent() { return weightScaleExponent; }
        /** Explicit snapshot only; callers must not allocate one per ordinary column. */
        public Decision decision() {
            int x=selectedSite%nx,z=selectedSite/nx;
            return new Decision(ALGORITHM,new Site(firstCellX+x,firstCellZ+z,siteX[selectedSite],siteZ[selectedSite],labels[selectedSite]),priority,fallback,retainedCount,total,collapsed,belowResolution,weightScaleExponent,noMatch);
        }
    }

    /** No per-column objects, hashes, streams or registry calls. Null is a typed scratch no-match. */
    public Candidate<B> select(BiomeCatalogFilter.Context context,long x,long z,Scratch s) {
        Objects.requireNonNull(context);
        if (s.owner!=this) throw new IllegalArgumentException("scratch belongs to another selector");
        if (x<s.minX || x>=s.maxX || z<s.minZ || z>=s.maxZ) throw new IllegalArgumentException("point outside scratch");
        s.retainedCount=0;s.total=0;s.collapsed=0;s.belowResolution=0;s.priority=0;s.fallback=false;s.weightScaleExponent=0;
        nearest(x,z,s);
        if (!active) { s.noMatch=NoMatch.INACTIVE_CATALOG; return null; }
        if (s.epoch==Integer.MAX_VALUE) { Arrays.fill(s.evaluatedEpoch,0);s.epoch=0; }
        s.epoch++;
        for (Tier tier:tiers) {
            double minimum=Double.POSITIVE_INFINITY;
            for (int index:tier.entries()) {
                int p=programOf[index];
                if (s.evaluatedEpoch[p]!=s.epoch) {
                    s.evaluatedEpoch[p]=s.epoch;s.admitted[p]=(byte)(programs[p].eligible(context)?1:0);s.programEvaluations++;
                }
                if (s.admitted[p]==0) continue;
                s.retained[s.retainedCount++]=index;
                double next=s.total+weights[index];if (next==s.total)s.collapsed++;
                s.total=next;minimum=Math.min(minimum,weights[index]);
            }
            if (s.retainedCount==0) continue;
            if (!(s.total>0) || !Double.isFinite(s.total)) throw new IllegalStateException("invalid retained weight total");
            s.priority=tier.priority();s.fallback=tier.fallback();s.noMatch=NoMatch.NONE;
            // Rare diagnostic path only. The normal all-unit tier needs no extra scan/divisions.
            double resolution=s.total*QUANTUM;
            if (minimum<resolution) for(int i=0;i<s.retainedCount;i++)if(weights[s.retained[i]]<resolution)s.belowResolution++;
            // A subnormal total would quantize U*total at the weight's own scale,
            // distorting even 1:3 proportions. Exact power-of-two scaling of this
            // retained tier avoids that bias; global scaling could erase another tier.
            s.weightScaleExponent=s.total<Double.MIN_NORMAL?1023:0;
            double samplingTotal=Math.scalb(s.total,s.weightScaleExponent);
            double target=samplingTarget(labelsAt(s),samplingTotal), cumulative=0;
            for (int i=0;i<s.retainedCount;i++) {
                int index=s.retained[i];cumulative+=s.weightScaleExponent==0?weights[index]:Math.scalb(weights[index],s.weightScaleExponent);
                if (target<cumulative) return candidates.get(index);
            }
            // Defensively retained positive/eligible only, never a zero/other-tier catalog tail.
            return candidates.get(s.retained[s.retainedCount-1]);
        }
        s.noMatch=NoMatch.NO_ELIGIBLE_POSITIVE_CANDIDATE;return null;
    }
    private static double labelsAt(CoherentBiomeSelector<?>.Scratch s) { return s.labels[s.selectedSite]; }
    static double samplingTarget(double quantile,double total) {
        if (!(quantile>=0 && quantile<1) || !(total>0) || !Double.isFinite(total)) throw new IllegalArgumentException("invalid CDF inputs");
        double target=quantile*total;
        return target<total?target:Math.nextDown(total);
    }
    private void nearest(long x,long z,Scratch s) {
        int h=config.spacingBlocks();long cx=Math.floorDiv(x,h),cz=Math.floorDiv(z,h);
        double best=Double.POSITIVE_INFINITY;long bestX=Long.MAX_VALUE,bestZ=Long.MAX_VALUE;
        for (long iz=cz-1;iz<=cz+1;iz++) for(long ix=cx-1;ix<=cx+1;ix++) {
            int index=(int)(iz-s.firstCellZ)*s.nx+(int)(ix-s.firstCellX);
            double dx=x-s.siteX[index],dz=z-s.siteZ[index],distance=dx*dx+dz*dz;s.distanceEvaluations++;
            if (distance<best || (distance==best && (ix<bestX || (ix==bestX && iz<bestZ)))) {
                best=distance;bestX=ix;bestZ=iz;s.selectedSite=index;
            }
        }
    }
    /** Explicit geometry inspection, independent of scratch/selection; not the column hot path. */
    public Site site(long cellX,long cellZ) {
        int h=config.spacingBlocks();long cellLimit=(WORLD_LIMIT+MAX_CANONICAL_TILE_SIZE-1)/h+4;
        if (cellX < -cellLimit || cellX>cellLimit || cellZ < -cellLimit || cellZ>cellLimit) throw new IllegalArgumentException("unsupported lattice site");
        long b=base(worldSeed,cellX,cellZ);
        return new Site(cellX,cellZ,position(cellX,h,uniform(mix64(b^JITTER_X))),position(cellZ,h,uniform(mix64(b^JITTER_Z))),uniform(mix64(b^LABEL)));
    }
    private static double position(long cell,int h,double u) { return (double)(cell*h)+h*0.5+h*(u*0.5-0.25); }
    private static long base(long seed,long x,long z) { return mix64(seed^mix64(x+X_DOMAIN)^Long.rotateLeft(mix64(z+Z_DOMAIN),32)); }
    private static double uniform(long value) { return (value>>>11)*QUANTUM; }
    private static long mix64(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
}
