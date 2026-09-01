package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.distantgen.VoxyDistantGen;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;


public class VoxyCommands {

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        var imports = Commands.literal("import")
                .then(Commands.literal("world")
                        .then(Commands.argument("world_name", StringArgumentType.string())
                                .suggests(VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(Commands.literal("raw")
                        .then(Commands.argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(Commands.literal("zip")
                        .then(Commands.argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(Commands.argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(Commands.literal("current")
                        .executes(ctx -> importCurrentWorldIn(ctx, false))
                        .then(Commands.literal("force")
                                .executes(ctx -> importCurrentWorldIn(ctx, true))))
                .then(Commands.literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        var debug = Commands.literal("debug")
                .then(Commands.literal("verifyTLNChildMask")
                        .executes(VoxyCommands::verifyTLNs)
                )
                .then(Commands.literal("heightmapDump")
                        .then(Commands.argument("ring", IntegerArgumentType.integer(0,
                                        me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapBuilder.RINGS - 1))
                                .executes(VoxyCommands::heightmapDump))
                );

        var distantGen = Commands.literal("distantgen")
                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1024))
                        .executes(VoxyCommands::distantGen));

        return Commands.literal("voxy")//.requires((ctx)-> VoxyCommon.getInstance() != null)
                .then(Commands.literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug)
                .then(distantGen);
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var wr = Minecraft.getInstance().levelRenderer;
        if (wr!=null) {
            ((IGetVoxyRenderSystem)wr).shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var r = Minecraft.getInstance().levelRenderer;
        if (r != null) r.allChanged();
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> ctx) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("How you even do this");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level));
        return 0;
    }

    // Phase 1 of the horizon-silhouette shadow plan: build the LOD terrain
    // heightmap from level-4 mip data and dump it as a PNG, so the data
    // source can be visually verified before anything in the render path
    // consumes it. Runs off-thread (uses acquireIfExists only — never forces
    // loads); result path is reported in chat and the log.
    private static int heightmapDump(CommandContext<CommandSourceStack> ctx) {
        var instance = VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var level = Minecraft.getInstance().level;
        var player = Minecraft.getInstance().player;
        if (level == null || player == null) {
            ctx.getSource().sendFailure(Component.literal("No active level"));
            return 1;
        }
        var engine = WorldIdentifier.ofEngineNullable(level);
        if (engine == null) {
            ctx.getSource().sendFailure(Component.literal("No voxy engine for current dimension"));
            return 1;
        }
        int ring = IntegerArgumentType.getInteger(ctx, "ring");
        int centerX = (int) player.getX();
        int centerZ = (int) player.getZ();
        ctx.getSource().sendSuccess(() -> Component.literal(
                "Building clipmap ring " + ring + " off-thread; result path will be reported in chat"), false);
        var thread = new Thread(() -> {
            try {
                var map = me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapBuilder.build(
                        engine, ring,
                        me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapBuilder.originSectionFor(ring, centerX),
                        me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapBuilder.originSectionFor(ring, centerZ),
                        new me.cortex.voxy.client.core.rendering.heightmap.TerrainHeightmapBuilder.FoliageCache());
                var target = new File(net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toFile(),
                        "voxy-heightmap-ring" + ring + ".png");
                map.dumpPng(target);
                Minecraft.getInstance().execute(() -> {
                    var p = Minecraft.getInstance().player;
                    if (p != null) {
                        p.displayClientMessage(Component.literal(
                                "Heightmap dumped to " + target.getAbsolutePath()), false);
                    }
                });
            } catch (Throwable t) {
                me.cortex.voxy.common.Logger.error("Heightmap dump failed: " + t.getMessage(), t);
            }
        }, "Voxy heightmap dump");
        thread.setDaemon(true);
        thread.start();
        return 0;
    }


    private static boolean fileBasedImporter(File directory) {
        return fileBasedImporter(directory, false);
    }

    private static boolean fileBasedImporter(File directory, boolean force) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine==null) return false;
        return instance.getImportManager().makeAndRunIfNone(engine, ()->{
            var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.setForceReimport(force);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        return fileBasedImporter(new File(ctx.getArgument("path", String.class)))?0:1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder sb) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), sb);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path dir, SuggestionsBuilder sb) {
        var str = sb.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (str.startsWith("\"")) {
            str = str.substring(1);
        }
        if (str.endsWith("\"")) {
            str = str.substring(0,str.length()-1);
        }
        var remaining = str;
        if (str.contains("/")) {
            int idx = str.lastIndexOf('/');
            remaining = str.substring(idx+1);
            try {
                dir = dir.resolve(str.substring(0, idx));
            } catch (Exception e) {
                return Suggestions.empty();
            }
            str = str.substring(0, idx+1);
        } else {
            str = "";
        }

        try {
            var worlds = Files.list(dir).toList();
            for (var world : worlds) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                var wn = world.getFileName().toString();
                if (wn.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, wn) || SharedSuggestionProvider.matchesSubStr(remaining, '"'+wn)) {
                    wn = str+wn + "/";
                    sb.suggest(StringArgumentType.escapeIfRequired(wn));
                }
            }
        } catch (IOException e) {}

        return sb.buildFuture();
    }


    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> ctx, boolean force) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            ctx.getSource().sendFailure(Component.translatable("You must be in single player to use this command"));
            return 1;
        }
        var regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if ((!regionPath.toFile().exists())||!regionPath.toFile().isDirectory()) {
            ctx.getSource().sendFailure(Component.translatable("Cannot find region folder for current dimension"));
            return 1;
        }
        if (force) {
            ctx.getSource().sendSuccess(() -> Component.literal("Forcing re-import — every chunk will be re-voxelised regardless of existing markers"), false);
        }
        return fileBasedImporter(regionPath.toFile(), force)?0:1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> ctx) {
        if (VoxyCommon.getInstance() == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var name = ctx.getArgument("world_name", String.class);
        var file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length()-1);
        }
        if (file.resolve("level.dat").toFile().exists()) {
            var dimFile = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimFile.isDirectory()) return 1;
            return fileBasedImporter(dimFile)?0:1;
        } else {
            if (!(name.endsWith("region"))) {
                file = file.resolve("region");
            }
            return fileBasedImporter(file.toFile()) ? 0 : 1;
        }
    }

    private static int importZip(CommandContext<CommandSourceStack> ctx) {
        var zip =  new File(ctx.getArgument("zipPath", String.class));
        var innerDir = "region/";
        try {
            innerDir = ctx.getArgument("innerPath", String.class);
        } catch (Exception e) {}

        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        String finalInnerDir = innerDir;

        var engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine != null) {
            return instance.getImportManager().makeAndRunIfNone(engine, () -> {
                var importer = new WorldImporter(engine, Minecraft.getInstance().level, instance.getServiceManager(), instance.savingServiceRateLimiter);
                importer.importZippedRegionDirectoryAsync(zip, finalInnerDir);
                return importer;
            }) ? 0 : 1;
        }
        return 1;
    }

    private static int distantGen(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (!VoxyConfig.CONFIG.distantGenEnabled) {
            ctx.getSource().sendFailure(Component.literal(
                    "Distant generation is disabled — set distantGenEnabled=true in voxy-config.json"));
            return 1;
        }
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            ctx.getSource().sendFailure(Component.literal("Distant gen requires single-player (Phase 1)"));
            return 1;
        }
        var clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) {
            ctx.getSource().sendFailure(Component.literal("No active level"));
            return 1;
        }
        var serverLevel = server.getLevel(clientLevel.dimension());
        if (serverLevel == null) {
            ctx.getSource().sendFailure(Component.literal("No server level for current dimension"));
            return 1;
        }
        var engine = WorldIdentifier.ofEngine(serverLevel);
        if (engine == null) {
            ctx.getSource().sendFailure(Component.literal("No voxy engine for current dimension"));
            return 1;
        }

        int radius = IntegerArgumentType.getInteger(ctx, "radius");
        var coord = VoxyDistantGen.getOrCreate(serverLevel, engine, instance.getServiceManager());
        coord.scheduler().setMaxInFlight(VoxyConfig.CONFIG.distantGenThreads);
        int submitted = coord.submitArea(radius);
        ctx.getSource().sendSuccess(
                () -> Component.literal("Distant-gen: submitted " + submitted + " chunks within radius " + radius
                        + " (threads=" + VoxyConfig.CONFIG.distantGenThreads + ")"),
                false);
        return 0;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> ctx) {
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            ctx.getSource().sendFailure(Component.translatable("Voxy must be enabled in settings to use this"));
            return 1;
        }
        var world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        if (world != null) {
            return instance.getImportManager().cancelImport(world)?0:1;
        }
        return 1;
    }
}