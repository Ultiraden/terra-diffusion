package com.github.xandergos.terraindiffusionmc.debug;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeDebugTrace;
import com.github.xandergos.terraindiffusionmc.catalog.BiomeCatalog;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Reads completed immutable developer traces and already loaded section palettes.
 * Never calls a world biome resolver, terrain provider, loading chunk getter or getChunkNow. */
public final class BiomeDebugCommand {
    private BiomeDebugCommand() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("packdebug").requires(s->s.hasPermission(2))
            .then(Commands.literal("biome").executes(c->atPlayer(c,false,1))
                .then(verbose(true))
                .then(page(true))
                .then(Commands.argument("x",IntegerArgumentType.integer(-29999984,29999984))
                    .then(Commands.argument("y",IntegerArgumentType.integer())
                        .then(Commands.argument("z",IntegerArgumentType.integer(-29999984,29999984))
                            .executes(c->atCoordinates(c,false,1)).then(verbose(false)).then(page(false)))))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> page(boolean player) {
        return Commands.literal("page").then(Commands.argument("page",IntegerArgumentType.integer(1))
            .executes(c->player ? atPlayer(c,false,IntegerArgumentType.getInteger(c,"page"))
                : atCoordinates(c,false,IntegerArgumentType.getInteger(c,"page"))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> verbose(boolean player) {
        return Commands.literal("verbose").executes(c->player ? atPlayer(c,true,1) : atCoordinates(c,true,1))
            .then(Commands.argument("page",IntegerArgumentType.integer(1))
                .executes(c->player ? atPlayer(c,true,IntegerArgumentType.getInteger(c,"page"))
                    : atCoordinates(c,true,IntegerArgumentType.getInteger(c,"page"))));
    }
    private static int atPlayer(CommandContext<CommandSourceStack> c,boolean verbose,int page) throws CommandSyntaxException {
        BlockPos p=c.getSource().getPlayerOrException().blockPosition();
        return inspect(c.getSource(),p.getX(),p.getY(),p.getZ(),verbose,page);
    }
    private static int atCoordinates(CommandContext<CommandSourceStack> c,boolean verbose,int page) {
        return inspect(c.getSource(),IntegerArgumentType.getInteger(c,"x"),IntegerArgumentType.getInteger(c,"y"),
            IntegerArgumentType.getInteger(c,"z"),verbose,page);
    }
    private static void line(CommandSourceStack source,String value) {
        source.sendSuccess(()->Component.literal(value),false);
    }
    public static int inspect(CommandSourceStack source,int x,int y,int z,boolean verbose,int page) {
        ServerLevel level=source.getLevel();
        if(level.dimension()!=Level.OVERWORLD || !(level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            source.sendFailure(Component.literal("Biome debug supports a Terrain Diffusion Overworld only.")); return 0;
        }
        if(level.isOutsideBuildHeight(y)) { source.sendFailure(Component.literal("Y is outside this world's build height.")); return 0; }
        int qx=QuartPos.fromBlock(x),qy=QuartPos.fromBlock(y),qz=QuartPos.fromBlock(z);
        int columnX=QuartPos.toBlock(qx),columnZ=QuartPos.toBlock(qz);
        line(source,"requested block="+x+","+y+","+z+" persisted quart="+qx+","+qy+","+qz+" classifier column="+columnX+","+columnZ);
        // NeoForge's pinned public visible-holder lookup is a map read. getNow does not wait or schedule.
        ChunkHolder holder=level.getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(x>>4,z>>4));
        LevelChunk chunk=holder==null ? null : holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
        if(chunk==null) line(source,"persisted quart biome unavailable: chunk not fully loaded");
        else {
            var biome=chunk.getSection(chunk.getSectionIndex(y)).getNoiseBiome(qx&3,qy&3,qz&3);
            line(source,"persisted quart biome="+biome.unwrapKey().map(k->k.location().toString()).orElse("unkeyed holder")
                +" (stored palette; surface trace may differ, including cave overrides)");
        }
        if(!BiomeDebugTrace.enabled()) { line(source,"precise trace unavailable: startup tracing disabled"); return 1; }
        if(!BiomeDebugTrace.configured(columnX,columnZ)) { line(source,"precise trace unavailable: column not preconfigured"); return 1; }
        int size=TerrainDiffusionConfig.tileSize();
        int i1=Math.floorDiv(columnZ,size)*size,j1=Math.floorDiv(columnX,size)*size;
        var key=new BiomeDebugTrace.TileKey(level.getSeed(),WorldScaleManager.getCurrentScale(),i1,j1,i1+size,j1+size);
        var found=BiomeDebugTrace.read(new BiomeDebugTrace.Position(columnX,columnZ),key);
        if(found.isEmpty()) { line(source,"precise trace unavailable: no successful canonical tile observation in this process"); return 1; }
        var r=found.get();
        line(source,"captured tile="+r.tile()+" source="+(r.tile().scale()<=1 ? "native" : "upsampled-smooth")+" pixel_size_m="+r.pixelSizeM());
        line(source,"captured catalog sha256="+r.catalogId()+" enabled="+r.catalogActive()+" resolved_entries="+r.catalogEntries());
        line(source,"policy="+r.policy()+" route="+r.route()+" selected_entry="+(r.selectedEntry()==null ? "none" : r.selectedEntry())
            +" surface="+r.surfaceBiome()+(r.genericSelection()==null?" short_id="+r.surfaceId():" palette_index="+r.genericSelection().paletteIndex()
                +" palette="+r.genericSelection().paletteId()+" spacing_world_blocks="+r.genericSelection().spacingWorldBlocks()
                +" cellular="+r.genericSelection().decision())+" snow_depth="+r.snowDepth()+" fallback="+r.fallback());
        if(!r.unavailable().isEmpty()) { line(source,"precise trace unavailable: "+r.unavailable()+"; no complete eligibility list retained"); return 1; }
        var context=r.context();
        line(source,"environment="+context.environment()+" wet_mask="+r.wet());
        for(var field:BiomeCatalog.Field.values()) {
            var value=context.numeric(field);
            String formatted=value.isEmpty() ? "unavailable" : Double.toString(value.getAsDouble());
            if(verbose && value.isPresent()) formatted+=" f32_bits=0x"+Integer.toHexString(Float.floatToRawIntBits((float)value.getAsDouble()));
            line(source,field+"="+formatted+" ["+unit(field)+"]");
        }
        StringBuilder states=new StringBuilder("predicates:");
        for(var predicate:BiomeCatalog.Predicate.values()) states.append(' ').append(predicate).append('=')
            .append(context.state(predicate).map(Object::toString).orElse("unavailable"));
        line(source,states.toString());
        line(source,"hard_eligible="+r.eligibleCount()+" rejected="+(r.entries().size()-r.eligibleCount())
            +"; hard eligibility is separate from selection; zero weight disables selection; bridge ignores generic priority/fallback tiers");
        var entries=verbose ? r.entries() : r.entries().stream().filter(BiomeDebugTrace.EntryResult::eligible).toList();
        int pages=Math.max(1,(entries.size()+BiomeDebugTrace.PAGE_SIZE-1)/BiomeDebugTrace.PAGE_SIZE);
        if(page>pages) { source.sendFailure(Component.literal("Page out of range: 1.."+pages)); return 0; }
        if(verbose) {
            line(source,"actual visited gates/choice="+String.join(";",r.gates()));
        }
        line(source,(verbose ? "all resolved catalog entries" : "hard-eligible catalog entries")+" page="+page+"/"+pages
            +" (ASCII ID order; "+(verbose ? "verbose <page>" : "page <page>")+" selects another page)");
        int start=(page-1)*BiomeDebugTrace.PAGE_SIZE,end=Math.min(entries.size(),start+BiomeDebugTrace.PAGE_SIZE);
        for(int n=start;n<end;n++) line(source,entries.get(n).line());
        return 1;
    }
    private static String unit(BiomeCatalog.Field field) {
        return switch(field) {
            case temperature_c,summer_max_c -> "C";
            case precipitation_mm -> "mm";
            case classification_elevation_m,physical_bathymetry_m -> "m";
            case water_depth_blocks -> "blocks";
            case growing_season_days -> "days";
            case temperature_seasonality -> "model seasonality / 100";
            case precipitation_cv -> "raw model p_cv";
            default -> "dimensionless";
        };
    }
}
