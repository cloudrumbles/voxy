package me.cortex.voxy.client;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.common.DebugUtils;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import me.cortex.voxy.commonImpl.importers.DHImporter;
import me.cortex.voxy.commonImpl.importers.WorldImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
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

public final class VoxyCommands {
    private VoxyCommands() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        LiteralArgumentBuilder<CommandSourceStack> imports = LiteralArgumentBuilder.<CommandSourceStack>literal("import")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("world")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("world_name", StringArgumentType.string())
                                .suggests((SuggestionProvider<CommandSourceStack>) VoxyCommands::importWorldSuggester)
                                .executes(VoxyCommands::importWorld)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("bobby")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("world_name", StringArgumentType.string())
                                .suggests((SuggestionProvider<CommandSourceStack>) VoxyCommands::importBobbySuggester)
                                .executes(VoxyCommands::importBobby)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("raw")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("path", StringArgumentType.string())
                                .executes(VoxyCommands::importRaw)))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("zip")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("zipPath", StringArgumentType.string())
                                .executes(VoxyCommands::importZip)
                                .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("innerPath", StringArgumentType.string())
                                        .executes(VoxyCommands::importZip))))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("current")
                        .executes(VoxyCommands::importCurrentWorldIn))
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("cancel")
                        .executes(VoxyCommands::cancelImport));

        if (DHImporter.HasRequiredLibraries) {
            imports = imports.then(LiteralArgumentBuilder.<CommandSourceStack>literal("distant_horizons")
                    .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument("sqlDbPath", StringArgumentType.string())
                            .executes(VoxyCommands::importDistantHorizons)));
        }

        LiteralArgumentBuilder<CommandSourceStack> debug = LiteralArgumentBuilder.<CommandSourceStack>literal("debug")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("verifyTLNChildMask")
                        .executes(context -> verifyTLNs(context, false))
                        .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("attemptRepair", BoolArgumentType.bool())
                                .executes(context -> verifyTLNs(context, BoolArgumentType.getBool(context, "attemptRepair")))));

        return LiteralArgumentBuilder.<CommandSourceStack>literal("voxy")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("reload")
                        .executes(VoxyCommands::reloadInstance))
                .then(imports)
                .then(debug);
    }

    private static int reloadInstance(CommandContext<CommandSourceStack> context) {
        VoxyClientInstance instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var worldRenderer = Minecraft.getInstance().levelRenderer;
        if (worldRenderer != null) {
            ((IGetVoxyRenderSystem) worldRenderer).voxy$shutdownRenderer();
        }

        VoxyCommon.shutdownInstance();
        System.gc();
        VoxyCommon.createInstance();

        var renderer = Minecraft.getInstance().levelRenderer;
        if (renderer != null) {
            renderer.allChanged();
        }
        return 0;
    }

    private static int verifyTLNs(CommandContext<CommandSourceStack> context, boolean attemptRepair) {
        if (VoxyCommon.getInstance() == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }
        if (Minecraft.getInstance().level == null) {
            throw new IllegalStateException("Cannot verify Voxy nodes outside a world");
        }
        DebugUtils.verifyAllTopLevelNodes(WorldIdentifier.ofEngine(Minecraft.getInstance().level), attemptRepair);
        return 0;
    }

    private static int importDistantHorizons(CommandContext<CommandSourceStack> context) {
        VoxyClientInstance instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        File databaseFile = new File(context.getArgument("sqlDbPath", String.class));
        if (!databaseFile.exists()) {
            return 1;
        }
        if (databaseFile.isDirectory()) {
            databaseFile = databaseFile.toPath().resolve("DistantHorizons.sqlite").toFile();
            if (!databaseFile.exists()) {
                return 1;
            }
        }

        File finalDatabaseFile = databaseFile;
        WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null) {
            return 1;
        }
        return instance.getImportManager().makeAndRunIfNone(engine,
                () -> new DHImporter(finalDatabaseFile, engine, Minecraft.getInstance().level,
                        instance.getServiceManager(), instance.savingServiceRateLimiter)) ? 0 : 1;
    }

    private static boolean fileBasedImporter(File directory) {
        VoxyClientInstance instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            return false;
        }

        WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null) {
            return false;
        }
        return instance.getImportManager().makeAndRunIfNone(engine, () -> {
            WorldImporter importer = new WorldImporter(engine, Minecraft.getInstance().level,
                    instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importRegionDirectoryAsync(directory);
            return importer;
        });
    }

    private static int importRaw(CommandContext<CommandSourceStack> context) {
        if (VoxyCommon.getInstance() == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }
        return fileBasedImporter(new File(context.getArgument("path", String.class))) ? 0 : 1;
    }

    private static int importBobby(CommandContext<CommandSourceStack> context) {
        if (VoxyCommon.getInstance() == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        File directory = new File(".bobby").toPath()
                .resolve(context.getArgument("world_name", String.class))
                .toFile();
        return fileBasedImporter(directory) ? 0 : 1;
    }

    private static CompletableFuture<Suggestions> importWorldSuggester(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve("saves"), builder);
    }

    private static CompletableFuture<Suggestions> importBobbySuggester(
            CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return fileDirectorySuggester(Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby"), builder);
    }

    private static CompletableFuture<Suggestions> fileDirectorySuggester(Path directory, SuggestionsBuilder builder) {
        String input = builder.getRemaining().replace("\\\\", "\\").replace("\\", "/");
        if (input.startsWith("\"")) {
            input = input.substring(1);
        }
        if (input.endsWith("\"")) {
            input = input.substring(0, input.length() - 1);
        }

        String remaining = input;
        String prefix;
        if (input.contains("/")) {
            int index = input.lastIndexOf('/');
            remaining = input.substring(index + 1);
            try {
                directory = directory.resolve(input.substring(0, index));
            } catch (RuntimeException exception) {
                return Suggestions.empty();
            }
            prefix = input.substring(0, index + 1);
        } else {
            prefix = "";
        }

        try (var worlds = Files.list(directory)) {
            for (Path world : worlds.toList()) {
                if (!world.toFile().isDirectory()) {
                    continue;
                }
                String worldName = world.getFileName().toString();
                if (worldName.equals(remaining)) {
                    continue;
                }
                if (SharedSuggestionProvider.matchesSubStr(remaining, worldName)
                        || SharedSuggestionProvider.matchesSubStr(remaining, '"' + worldName)) {
                    builder.suggest(StringArgumentType.escapeIfRequired(prefix + worldName + "/"));
                }
            }
        } catch (IOException ignored) {
        }

        return builder.buildFuture();
    }

    private static int importCurrentWorldIn(CommandContext<CommandSourceStack> context) {
        if (VoxyCommon.getInstance() == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        var localServer = Minecraft.getInstance().getSingleplayerServer();
        if (localServer == null) {
            sendErrorToPlayer(Component.literal("You must be in single player to use this command"));
            return 1;
        }

        Path regionPath = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(),
                localServer.getWorldPath(LevelResource.ROOT)).resolve("region");
        if (!Files.isDirectory(regionPath)) {
            sendErrorToPlayer(Component.literal("Cannot find region folder for current dimension"));
            return 1;
        }
        return fileBasedImporter(regionPath.toFile()) ? 0 : 1;
    }

    private static int importWorld(CommandContext<CommandSourceStack> context) {
        if (VoxyCommon.getInstance() == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        String name = context.getArgument("world_name", String.class);
        Path file = new File("saves").toPath().resolve(name);
        name = name.toLowerCase(Locale.ROOT);
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }

        if (Files.exists(file.resolve("level.dat"))) {
            File dimensionDirectory = DimensionType.getStorageFolder(Minecraft.getInstance().level.dimension(), file)
                    .resolve("region")
                    .toFile();
            if (!dimensionDirectory.isDirectory()) {
                return 1;
            }
            return fileBasedImporter(dimensionDirectory) ? 0 : 1;
        }

        if (!name.endsWith("region")) {
            file = file.resolve("region");
        }
        return fileBasedImporter(file.toFile()) ? 0 : 1;
    }

    private static int importZip(CommandContext<CommandSourceStack> context) {
        File zip = new File(context.getArgument("zipPath", String.class));
        String innerDirectory = "region/";
        try {
            innerDirectory = context.getArgument("innerPath", String.class);
        } catch (IllegalArgumentException ignored) {
        }

        VoxyClientInstance instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        String finalInnerDirectory = innerDirectory;
        WorldEngine engine = WorldIdentifier.ofEngine(Minecraft.getInstance().level);
        if (engine == null) {
            return 1;
        }

        return instance.getImportManager().makeAndRunIfNone(engine, () -> {
            WorldImporter importer = new WorldImporter(engine, Minecraft.getInstance().level,
                    instance.getServiceManager(), instance.savingServiceRateLimiter);
            importer.importZippedRegionDirectoryAsync(zip, finalInnerDirectory);
            return importer;
        }) ? 0 : 1;
    }

    private static int cancelImport(CommandContext<CommandSourceStack> context) {
        VoxyClientInstance instance = (VoxyClientInstance) VoxyCommon.getInstance();
        if (instance == null) {
            sendErrorToPlayer(Component.literal("Voxy must be enabled in settings to use this"));
            return 1;
        }

        WorldEngine world = WorldIdentifier.ofEngineNullable(Minecraft.getInstance().level);
        return world != null && instance.getImportManager().cancelImport(world) ? 0 : 1;
    }

    private static void sendErrorToPlayer(Component component) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(component, false);
        }
    }
}
