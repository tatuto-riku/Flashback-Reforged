package com.moulberry.flashback;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.realmsclient.RealmsMainScreen;
import com.mojang.serialization.Lifecycle;
import com.moulberry.flashback.action.*;
import com.moulberry.flashback.combo_options.MarkerColour;
import com.moulberry.flashback.command.BetterColorArgument;
import com.moulberry.flashback.compat.DistantHorizonsSupport;
import com.moulberry.flashback.compat.simple_voice_chat.SimpleVoiceChatPlayback;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.exporting.AsyncFileDialogs;
import com.moulberry.flashback.exporting.ExportJob;
import com.moulberry.flashback.exporting.taskbar.TaskbarManager;
import com.moulberry.flashback.ext.MinecraftExt;
import com.moulberry.flashback.keyframe.KeyframeRegistry;
import com.moulberry.flashback.keyframe.types.*;
import com.moulberry.flashback.packet.*;
import com.moulberry.flashback.playback.EmptyLevelSource;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import com.moulberry.flashback.record.Recorder;
import com.moulberry.flashback.record.ReplayExporter;
import com.moulberry.flashback.record.ReplayMarker;
import com.moulberry.flashback.screen.RecoverRecordingsScreen;
import com.moulberry.flashback.screen.SaveReplayScreen;
import com.moulberry.flashback.screen.UnsupportedLoaderScreen;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.flashback.visuals.AccurateEntityPositionHandler;
import com.moulberry.flashback.visuals.ShaderManager;

import com.seibel.distanthorizons.api.DhApi;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.FileUtil;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ClientConfigurationPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.*;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Mod("flashback_reforged")
public class Flashback {
    public static final Logger LOGGER = LoggerFactory.getLogger("flashback");

    public static final int MAGIC = 0xD780E884;
    public static volatile Recorder RECORDER = null;
    public static ExportJob EXPORT_JOB = null;
    private static FlashbackConfigV1 config;
    public static Object configElements = null;
    private static Path configDirectory = null;

    private static int delayedStartRecording = 0;
    private static boolean delayedOpenConfig = false;
    private static volatile boolean isInReplay = false;

    public static boolean supportsDistantHorizons = false;

    public static boolean isBobbyLoaded = false;

    private static final List<Path> pendingReplaySave = new ArrayList<>();
    private static final List<Path> pendingReplayRecovery = new ArrayList<>();
    private static List<String> pendingUnsupportedModsForRecording = null;

    private static boolean isOpeningReplay = false;

    // Buffered AdvancedAddEntityPayloads that arrived before the target entity existed on the
    // client (e.g. due to snapshot packet ordering). Retried each client tick until applied.
    private static final List<ClientboundCustomPayloadPacket> pendingAdvancedAddEntityPayloads = new ArrayList<>();

    // Cache for the Create contraption-visual refresh (resolved lazily; Create may be absent).
    private static boolean createVisualResolved = false;
    private static java.lang.reflect.Method createGetContraption = null;
    private static java.lang.reflect.Method createGetClientContraption = null;
    private static java.lang.reflect.Field createStructureVersionField = null;
    private static java.lang.reflect.Field createChildrenVersionField = null;
    private static java.lang.reflect.Field createContraptionField = null;

    // Contraption entity ids currently tracked on the client level (populated on join, cleared on
    // leave). Polled each client tick to detect contraption (re)loads in EITHER direction.
    private static final java.util.Set<Integer> contraptionEntitiesToRefresh = new java.util.HashSet<>();
    // Contraption entity ids that must have their structure rebuilt as soon as their contraption
    // becomes available. Unlike contraptionLastInstance (which only bumps on an instance CHANGE),
    // this covers entities whose contraption instance is STABLE across a re-add (e.g. Create train
    // carriages: the CarriageContraption is the same object before/after a replay scrub), so the
    // instance-change check alone would never re-trigger a rebuild after scrubbing. Set on join.
    private static final java.util.Set<Integer> contraptionEntitiesNeedingBump = new java.util.HashSet<>();
    // Last seen Contraption instance per tracked entity id. Used to detect when a contraption's
    // data is (re)loaded (initial spawn, scrub forward, scrub backward) by comparing the instance
    // reference; a change forces a structure rebuild regardless of playback direction.
    private static final java.util.Map<Integer, Object> contraptionLastInstance = new java.util.HashMap<>();
    // Contraption instances cached when an entity is discarded (e.g. during a replay snapshot
    // scrub). Re-applied when the entity is re-added so the structure can be rebuilt even if the
    // contraption spawn payload was not re-sent by the snapshot (common when scrubbing backward).
    private static final java.util.Map<Integer, Object> cachedContraptions = new java.util.HashMap<>();
    private static Class<?> createAbstractContraptionEntityClass = null;
    private static boolean createAceResolved = false;

    private static boolean isCreateContraptionEntity(Entity entity) {
        if (createAceResolved) {
            return createAbstractContraptionEntityClass != null && createAbstractContraptionEntityClass.isInstance(entity);
        }
        createAceResolved = true;
        try {
            createAbstractContraptionEntityClass = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
        } catch (Throwable ignored) {
            createAbstractContraptionEntityClass = null;
        }
        return createAbstractContraptionEntityClass != null && createAbstractContraptionEntityClass.isInstance(entity);
    }

    private static Class<?> createCarriageContraptionEntityClass = null;
    private static boolean createCceResolved = false;
    private static boolean isCreateCarriageEntity(Entity entity) {
        if (createCceResolved) {
            return createCarriageContraptionEntityClass != null && createCarriageContraptionEntityClass.isInstance(entity);
        }
        createCceResolved = true;
        try {
            createCarriageContraptionEntityClass = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
        } catch (Throwable ignored) {
            createCarriageContraptionEntityClass = null;
        }
        return isCreateCarriageEntity(entity);
    }

    // ---------------------------------------------------------------------------------------------
    // Aeronautics SubLevel support (resolved lazily via reflection; Aeronautics may be absent at runtime).
    //
    // Create contraptions that live on an Aeronautics SubLevel (e.g. a Create: Aeronautics airship whose
    // propeller-bearing drives a moving block) are NOT reachable through the main client level's
    // by-id entity lookup: the SubLevelInclusiveLevelEntityGetter only traverses SubLevels for AABB
    // queries, while getEntity(id) only checks the main store. So during a replay, the
    // AdvancedAddEntityPayload carrying the contraption's spawn data is buffered by Flashback and its
    // retry loop can never find the entity on the main level, leaving the contraption permanently
    // invisible. We therefore resolve sub-level entities ourselves and apply the spawn data directly.
    // ---------------------------------------------------------------------------------------------

    private static boolean aeronauticsResolved = false;
    private static Class<?> aeronauticsSubLevelContainerClass = null;
    private static Method aeronauticsGetContainerMethod = null;
    private static Class<?> aeronauticsClientSubLevelContainerClass = null;
    private static Method aeronauticsGetAllSubLevelsMethod = null;
    private static Method aeronauticsSubLevelGetLevelMethod = null;

    private static void ensureAeronauticsResolved() {
        if (aeronauticsResolved) {
            return;
        }
        aeronauticsResolved = true;
        try {
            aeronauticsSubLevelContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            aeronauticsGetContainerMethod = aeronauticsSubLevelContainerClass.getMethod("getContainer", Level.class);
            aeronauticsClientSubLevelContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer");
            aeronauticsGetAllSubLevelsMethod = aeronauticsClientSubLevelContainerClass.getMethod("getAllSubLevels");
            Class<?> clientSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ClientSubLevel");
            aeronauticsSubLevelGetLevelMethod = clientSubLevelClass.getMethod("getLevel");
        } catch (final Throwable t) {
            aeronauticsSubLevelContainerClass = null;
            aeronauticsGetContainerMethod = null;
            aeronauticsClientSubLevelContainerClass = null;
            aeronauticsGetAllSubLevelsMethod = null;
            aeronauticsSubLevelGetLevelMethod = null;
            Flashback.LOGGER.error("Aeronautics sub-level API resolution FAILED: {}", t);
        }
    }

    /**
     * Finds an entity by id, searching the main level first and then every Aeronautics SubLevel. Returns null
     * if the entity cannot be found anywhere. Unlike {@code Level.getEntity(int)} this reaches entities
     * that Aeronautics stores inside a SubLevel's own Level, which is where Create contraptions on a
     * physicized structure (airship, etc.) live during a replay.
     */
    private static Entity findEntityIncludingSubLevels(final Level level, final int id) {
        final Entity main = level.getEntity(id);
        if (main != null) {
            return main;
        }
        ensureAeronauticsResolved();
        if (aeronauticsSubLevelContainerClass == null || aeronauticsGetContainerMethod == null
            || aeronauticsClientSubLevelContainerClass == null || aeronauticsGetAllSubLevelsMethod == null
            || aeronauticsSubLevelGetLevelMethod == null) {
            return null;
        }
        try {
            final Object container = aeronauticsGetContainerMethod.invoke(null, level);
            if (container == null || !aeronauticsClientSubLevelContainerClass.isInstance(container)) {
                return null;
            }
            final Object allSubLevels = aeronauticsGetAllSubLevelsMethod.invoke(container);
            if (allSubLevels instanceof Collection) {
                for (final Object subLevel : (Collection<?>) allSubLevels) {
                    if (subLevel == null) {
                        continue;
                    }
                    try {
                        final Object subLevelLevel = aeronauticsSubLevelGetLevelMethod.invoke(subLevel);
                        if (subLevelLevel instanceof Level) {
                            final Entity e = ((Level) subLevelLevel).getEntity(id);
                            if (e != null) {
                                return e;
                            }
                        }
                    } catch (final Throwable t) {
                        // Reflection/Aeronautics API mismatch on this sub-level — skip it.
                    }
                }
            }
        } catch (final Throwable ignored) {
            // Reflection/Aeronautics API mismatch — fall through to null.
        }
        return null;
    }

    /**
     * Applies a NeoForge {@code AdvancedAddEntityPayload}'s spawn data to an entity that lives on a
     * Aeronautics SubLevel. We cannot use {@code packet.handle(connection)} here because that internally
     * re-looks-up the entity through the MAIN level (which never finds a SubLevel entity). Instead we
     * call {@code readSpawnData} directly on the resolved SubLevel entity, mirroring NeoForge's own
     * AdvancedAddEntityPayload handler. This is what restores the Create contraption so it can render.
     */
    private static boolean applyComplexSpawnPayload(final Entity target, final byte[] customPayload, final LocalPlayer localPlayer) {
        if (!(target instanceof IEntityWithComplexSpawn complex)) {
            return false;
        }
        try {
            final RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(customPayload), localPlayer.registryAccess());
            complex.readSpawnData(buf);
            return true;
        } catch (final Throwable t) {
            Flashback.LOGGER.error("Failed to apply complex spawn payload to id={}: {}", target.getId(), t);
            return false;
        }
    }

    /**
     * Force a Create contraption entity's Flywheel visual to (re)build its structure.
     *
     * Contraption entities (Create) are rendered by {@code ContraptionVisual} (a Flywheel
     * DynamicVisual). That visual is constructed when the entity is added to the client level,
     * which in a replay happens BEFORE the contraption is restored from the AdvancedAddEntityPayload
     * (readSpawnData). When the contraption is still null at construction, setupStructure() is
     * skipped and lastStructureVersion stays 0. beginFrame() only rebuilds the structure when
     * lastStructureVersion differs from ClientContraption.structureVersion(); in a replay both are
     * 0, so the structure is never built and the contraption renders nothing.
     *
     * We cannot reach the Flywheel visual directly (Flywheel is jarjared inside Create and invisible
     * to this mod's classloader), so instead we bump ClientContraption's structureVersion (and
     * childrenVersion) to a non-zero value. On the next beginFrame the mismatch forces
     * setupStructure() to run and the structure blocks are drawn.
     */
    private static void ensureCreateVisualResolved() {
        if (createVisualResolved) {
            return;
        }
        createVisualResolved = true;
        try {
            Class<?> aceClass = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
            Class<?> contraptionClass = Class.forName("com.simibubi.create.content.contraptions.Contraption");
            Class<?> ccClass = Class.forName("com.simibubi.create.content.contraptions.render.ClientContraption");
            createGetContraption = aceClass.getMethod("getContraption");
            createGetClientContraption = contraptionClass.getMethod("getOrCreateClientContraptionLazy");
            createStructureVersionField = ccClass.getDeclaredField("structureVersion");
            createStructureVersionField.setAccessible(true);
            createChildrenVersionField = ccClass.getDeclaredField("childrenVersion");
            createChildrenVersionField.setAccessible(true);
            try {
                createContraptionField = aceClass.getDeclaredField("contraption");
                createContraptionField.setAccessible(true);
            } catch (Throwable t) {
                createContraptionField = null;
                Flashback.LOGGER.error("Could not resolve AbstractContraptionEntity.contraption field: {}", t);
            }
        } catch (Throwable t) {
            createGetContraption = null;
            Flashback.LOGGER.error("Failed to resolve Create contraption-visual methods: {}", t);
        }
    }

    public static boolean refreshContraptionVisual(Entity entity) {
        // Only meaningful for actual Create contraption entities. Calling getContraption() on a
        // non-contraption entity (e.g. SuperGlueEntity) throws IllegalArgumentException, so guard it.
        if (!isCreateContraptionEntity(entity)) {
            return false;
        }
        ensureCreateVisualResolved();
        if (createGetContraption == null) {
            return false;
        }
        try {
            Object contraption = createGetContraption.invoke(entity);
            if (contraption == null) {
                return false;
            }
            Object clientContraption = createGetClientContraption.invoke(contraption);
            if (clientContraption == null) {
                return false;
            }
            // Bump BOTH versions so beginFrame() sees a mismatch and rebuilds the structure. This
            // happens on every contraption-data change (initial spawn AND every replay scrub), so
            // we forcibly increment the version rather than only acting when it is still 0. Callers
            // ensure this is invoked at most once per data change, so we never rebuild every frame.
            int oldVer = (int) createStructureVersionField.get(clientContraption);
            createStructureVersionField.set(clientContraption, oldVer + 1);
            int oldChild = (int) createChildrenVersionField.get(clientContraption);
            createChildrenVersionField.set(clientContraption, oldChild + 1);
            contraptionLastInstance.put(entity.getId(), contraption);
            return true;
            } catch (Throwable t) {
                Flashback.LOGGER.error("Failed to bump ClientContraption version id={}: {}", entity.getId(), t);
                return false;
            }
    }

    public static long worldBorderLerpStartTime = -1L;

    public static final KeyMapping createMarker1KeyBind = new KeyMapping("flashback.keybind.create_marker_1",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "flashback.keybind");
    public static final KeyMapping createMarker2KeyBind = new KeyMapping("flashback.keybind.create_marker_2",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "flashback.keybind");
    public static final KeyMapping createMarker3KeyBind = new KeyMapping("flashback.keybind.create_marker_3",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "flashback.keybind");
    public static final KeyMapping createMarker4KeyBind = new KeyMapping("flashback.keybind.create_marker_4",
        InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), "flashback.keybind");

    public Flashback() {
        // Client-side initialization
        if (FMLEnvironment.dist == Dist.CLIENT) {
            clientInit();
        }

        // Register game (forge) bus events
        IEventBus forgeBus = NeoForge.EVENT_BUS;
        forgeBus.addListener(this::onServerTickEnd);
        forgeBus.addListener(this::onServerTickStart);
        forgeBus.addListener(this::onEntityJoinLevel);
        forgeBus.addListener(this::onEntityLeaveLevel);
    }

    // ---- Client initialization (was onInitializeClient) ----

    private void clientInit() {
        Path configFolder = FMLPaths.CONFIGDIR.get().resolve("flashback");

        try {
            Files.createDirectories(configFolder);
        } catch (IOException e) {
            Flashback.LOGGER.error("Failed to create config folder", e);
        }

        config = FlashbackConfigV1.tryLoadFromFolder(configFolder);
        // Lattice config GUI not available on NeoForge with official mappings

        TempFolderProvider.tryDeleteStaleFolders(TempFolderProvider.TempFolderType.SERVER);

        Path recordingFolder = TempFolderProvider.getTypedTempFolder(TempFolderProvider.TempFolderType.RECORDING);
        if (Files.exists(recordingFolder)) {
            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(recordingFolder)) {
                Iterator<Path> iterator = directoryStream.iterator();
                while (iterator.hasNext()) {
                    Path folder = iterator.next();

                    if (Files.exists(folder.resolve("metadata.json"))) {
                        pendingReplayRecovery.add(folder);
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        if (pendingReplayRecovery.isEmpty()) {
            TempFolderProvider.tryDeleteStaleFolders(TempFolderProvider.TempFolderType.RECORDING);
        }

        // Delete partial exports
        try {
            FileUtils.deleteDirectory(Path.of("replay_export_temp").toFile());
        } catch (Exception ignored) {}

        this.deleteUnusedReplayStates();

        ActionRegistry.register(ActionNextTick.INSTANCE);
        ActionRegistry.register(ActionGamePacket.INSTANCE);
        ActionRegistry.register(ActionConfigurationPacket.INSTANCE);
        ActionRegistry.register(ActionCreateLocalPlayer.INSTANCE);
        ActionRegistry.register(ActionMoveEntities.INSTANCE);
        ActionRegistry.register(ActionLevelChunkCached.INSTANCE);
        ActionRegistry.register(ActionAccuratePlayerPosition.INSTANCE);

        KeyframeRegistry.register(CameraKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraOrbitKeyframeType.INSTANCE);
        KeyframeRegistry.register(TrackEntityKeyframeType.INSTANCE);
        KeyframeRegistry.register(CameraShakeKeyframeType.INSTANCE);
        KeyframeRegistry.register(FOVKeyframeType.INSTANCE);
        KeyframeRegistry.register(SpeedKeyframeType.INSTANCE);
        KeyframeRegistry.register(TimelapseKeyframeType.INSTANCE);
        KeyframeRegistry.register(TimeOfDayKeyframeType.INSTANCE);
        KeyframeRegistry.register(FreezeKeyframeType.INSTANCE);
        KeyframeRegistry.register(BlockOverrideKeyframeType.INSTANCE);
        KeyframeRegistry.register(AudioKeyframeType.INSTANCE);

        ShaderManager.INSTANCE.register();

        AtomicReference<String> unsupportedLoader = new AtomicReference<>(findUnsupportedLoaders());

        // Register client tick events on forge bus
        IEventBus forgeBus = NeoForge.EVENT_BUS;

        forgeBus.addListener((ClientTickEvent.Post event) -> {
            var minecraft = Minecraft.getInstance();
            updateIsInReplay();

            // Keep the client's registry access cached while connected to a real world, so the embedded
            // replay server can use it to fill in missing entries (e.g. minecraft:rhombus banner pattern)
            // even when a replay is opened later from the title screen. Don't overwrite it while inside
            // a replay, as that registry is itself incomplete.
            if (!Flashback.isInReplay() && minecraft.getConnection() != null) {
                Flashback.clientRegistryAccess = minecraft.getConnection().registryAccess();
            }

            AccurateEntityPositionHandler.tick();

            // Fix for camera entity sometimes being incorrect when respawning
            Entity camera = Minecraft.getInstance().cameraEntity;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && camera != null && camera != player) {
                if (camera.isRemoved()) {
                    Entity other = player.level().getEntity(camera.getId());
                    if (other != null && !other.isRemoved()) {
                        Minecraft.getInstance().setCameraEntity(other);
                    }
                }
            }

            Flashback.getConfig().tickDelayedSave();

            // Poll Create contraption entities to detect when their contraption data is (re)loaded,
            // in EITHER direction (initial spawn, scrub forward, scrub backward). We track the
            // Contraption instance per entity; whenever it changes from the last observed instance
            // (including a null->instance transition after the entity is (re)added during a scrub),
            // we bump ClientContraption.structureVersion so beginFrame() rebuilds the Flywheel
            // structure. This is robust to rewind because the contraption is re-assigned a new
            // instance when its spawn data is applied, regardless of playback direction.
            if (!Flashback.isInReplay()) {
                contraptionEntitiesToRefresh.clear();
                contraptionLastInstance.clear();
                cachedContraptions.clear();
                contraptionEntitiesNeedingBump.clear();
            } else if (!contraptionEntitiesToRefresh.isEmpty()) {
                LocalPlayer lp = Minecraft.getInstance().player;
                if (lp != null && lp.level() != null) {
                    ensureCreateVisualResolved();
                    Iterator<Integer> it = contraptionEntitiesToRefresh.iterator();
                    while (it.hasNext()) {
                        int id = it.next();
                        Entity e = findEntityIncludingSubLevels(lp.level(), id);
                        if (e == null || e.isRemoved()) {
                            it.remove();
                            contraptionLastInstance.remove(id);
                            contraptionEntitiesNeedingBump.remove(id);
                            continue;
                        }
                        if (isCreateContraptionEntity(e)) {
                            Object current = null;
                            try {
                                if (createGetContraption != null) {
                                    current = createGetContraption.invoke(e);
                                }
                            } catch (Throwable ignored) {}
                            Object last = contraptionLastInstance.get(id);
                            boolean changed = current != null && current != last;
                            boolean needsBump = contraptionEntitiesNeedingBump.contains(id);
                            // Bump when the contraption instance changed OR when this entity was
                            // (re)added and still needs its structure built. The latter is required
                            // for Create train carriages, whose contraption instance is stable across
                            // a replay scrub, so the instance-change check alone would never re-trigger
                            // a rebuild after scrubbing (leaving the structure invisible/wrong).
                            if (current != null && (changed || needsBump)) {
                                Flashback.refreshContraptionVisual(e);
                                contraptionEntitiesNeedingBump.remove(id);
                            }
                        } else {
                            it.remove();
                            contraptionLastInstance.remove(id);
                            contraptionEntitiesNeedingBump.remove(id);
                        }
                    }
                }
            }

            // Retry AdvancedAddEntityPayloads that arrived before their entity existed
            if (!pendingAdvancedAddEntityPayloads.isEmpty()) {
                LocalPlayer lp = Minecraft.getInstance().player;
                if (lp != null && lp.level() != null) {
                    var connection = lp.connection;
                    Iterator<ClientboundCustomPayloadPacket> iterator = pendingAdvancedAddEntityPayloads.iterator();
                    while (iterator.hasNext()) {
                        ClientboundCustomPayloadPacket packet = iterator.next();
                        AdvancedAddEntityPayload payload = (AdvancedAddEntityPayload) packet.payload();
                        Entity e = findEntityIncludingSubLevels(lp.level(), payload.entityId());
                        if (e != null) {
                            // For a Main-level entity, packet.handle() resolves the entity itself and applies the
                            // spawn data. For an Aeronautics SubLevel entity the handle() path cannot find the entity
                            // (it only searches the main level), so apply the spawn data directly.
                            if (e.level() == lp.level()) {
                                packet.handle(connection);
                            } else {
                                applyComplexSpawnPayload(e, payload.customPayload(), lp);
                            }
                            // The Flywheel ContraptionVisual is created when the entity spawns (contraption still
                            // null at that point), so it renders nothing. Now that readSpawnData has restored the
                            // contraption, bump the ClientContraption version so beginFrame rebuilds the structure.
                            Flashback.refreshContraptionVisual(e);
                            iterator.remove();
                        } else {
                            // Entity not present on the client yet; will be retried on the next tick.
                        }
                    }
                }
            }
        });

        forgeBus.addListener((ClientTickEvent.Pre event) -> {
            var minecraft = Minecraft.getInstance();
            if (RECORDER != null && Flashback.config.advanced.synchronizeTicking && minecraft.hasSingleplayerServer()) {
                boolean isLevelLoaded = !(minecraft.screen instanceof ReceivingLevelScreen);
                boolean willRecord = minecraft.level != null && (minecraft.getOverlay() == null || !minecraft.getOverlay().isPauseScreen()) &&
                    !minecraft.isPaused() && !RECORDER.isPaused() && isLevelLoaded;
                while (willRecord && !SYNCHRONIZE_TICKING_CLIENT_FLAG.compareAndSet(true, false)) {
                    LockSupport.parkNanos("flashback synchronized ticking: waiting for server", 100000L);
                }
            }

            if (canReplaceScreen(minecraft.screen)) {
                openNewScreen(unsupportedLoader, minecraft.screen);
            }

            if (minecraft.level != null && delayedStartRecording > 0) {
                IntegratedServer integratedServer = minecraft.getSingleplayerServer();
                if (integratedServer != null && integratedServer.getClass() != IntegratedServer.class) {
                    delayedStartRecording = 0;
                } else if (Flashback.getConfig().recordingControls.automaticallyStart && RECORDER == null) {
                    delayedStartRecording -= 1;
                    if (delayedStartRecording == 0) {
                        startRecordingReplay();
                    }
                } else {
                    delayedStartRecording = 0;
                }
            }

            updateIsInReplay();

            if (createMarker1KeyBind.consumeClick()) {
                addMarker(Flashback.config.marker.markerOptions1);
            }
            if (createMarker2KeyBind.consumeClick()) {
                addMarker(Flashback.config.marker.markerOptions2);
            }
            if (createMarker3KeyBind.consumeClick()) {
                addMarker(Flashback.config.marker.markerOptions3);
            }
            if (createMarker4KeyBind.consumeClick()) {
                addMarker(Flashback.config.marker.markerOptions4);
            }
        });

        // Client connection join event
        forgeBus.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            // Cache the client's registry access from the (real) world we just joined. This is used by
            // the embedded replay server to fill in registry entries it would otherwise be missing
            // (e.g. the minecraft:rhombus banner pattern), which would disconnect the viewer.
            if (!Flashback.isInReplay() && Minecraft.getInstance().getConnection() != null) {
                Flashback.clientRegistryAccess = Minecraft.getInstance().getConnection().registryAccess();
                var bp = Flashback.getBannerPatternRegistry(Flashback.clientRegistryAccess);
            }
            if (!Flashback.isInReplay() && Flashback.getConfig().recordingControls.automaticallyStart && RECORDER == null) {
                delayedStartRecording = 20;
            }
            if (ModList.get().isLoaded("voicechat")) {
                SimpleVoiceChatPlayback.cleanUp();
            }
        });

        if (ModList.get().isLoaded("distanthorizons")) {
            if (DhApi.getApiMajorVersion() >= 4) {
                Flashback.LOGGER.info("DistantHorizons detected. Enabling Flashback+DistantHorizons integration");
                supportsDistantHorizons = true;
                DistantHorizonsSupport.register();
            } else {
                Flashback.LOGGER.error("DistantHorizons is installed, but API version is too low ({}). Disabling integration.", DhApi.getApiMajorVersion());
            }
        }

        if (ModList.get().isLoaded("bobby")) {
            isBobbyLoaded = true;
        }
    }

    // ---- Server tick events ----

    private final AtomicBoolean synchronizeTickingCanTickServer = new AtomicBoolean(true);
    private static final AtomicBoolean SYNCHRONIZE_TICKING_CLIENT_FLAG = new AtomicBoolean(true);

    private void onServerTickEnd(ServerTickEvent.Post event) {
        SYNCHRONIZE_TICKING_CLIENT_FLAG.set(true);
    }

    private void onServerTickStart(ServerTickEvent.Pre event) {
        if (RECORDER != null && Flashback.config.advanced.synchronizeTicking) {
            while (!synchronizeTickingCanTickServer.compareAndSet(true, false)) {
                LockSupport.parkNanos("flashback synchronized ticking: waiting for client", 100000L);
            }
        }
    }

    // Track Create contraption entities joining the client level so their Flywheel visual can be
    // rebuilt once the contraption is restored (covers initial spawn AND re-spawn during scrubbing).
    private void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!Flashback.isInReplay()) {
            return;
        }
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (isCreateContraptionEntity(entity)) {
            int id = entity.getId();
            contraptionEntitiesToRefresh.add(id);
            // Force a structure rebuild once the contraption becomes available. Required for Create
            // train carriages whose contraption instance is stable across a replay scrub, so the
            // instance-change poll would otherwise never re-trigger a rebuild after re-adding.
            contraptionEntitiesNeedingBump.add(id);
            // When scrubbing backward (or any snapshot jump) the contraption spawn payload may not
            // be re-sent, leaving contraption null and the structure invisible. If we cached the
            // contraption when this entity was discarded, re-apply it now and force a rebuild.
            Object cached = cachedContraptions.remove(id);
            if (cached != null) {
                try {
                    ensureCreateVisualResolved();
                    if (createContraptionField != null) {
                        createContraptionField.set(entity, cached);
                        Flashback.refreshContraptionVisual(entity);
                    }
                } catch (Throwable t) {
                    Flashback.LOGGER.error("Failed to re-apply cached contraption id={}: {}", id, t);
                }
            }
        }
    }

    private void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        int id = event.getEntity().getId();
        contraptionEntitiesToRefresh.remove(id);
        contraptionLastInstance.remove(id);
        contraptionEntitiesNeedingBump.remove(id);
        // Cache the contraption so it can be re-applied if the entity is re-added during a scrub
        // (e.g. the entity is discarded by a replay snapshot and re-created without its spawn data).
        if (Flashback.isInReplay() && isCreateContraptionEntity(event.getEntity())) {
            try {
                ensureCreateVisualResolved();
                if (createContraptionField != null) {
                    Object c = createContraptionField.get(event.getEntity());
                    if (c != null) {
                        cachedContraptions.put(id, c);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- Mod bus event subscribers ----

    @EventBusSubscriber(modid = "flashback_reforged", bus = EventBusSubscriber.Bus.MOD)
    public static class ModEvents {
        @SubscribeEvent
        public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
            // All of these payloads are only exchanged with Flashback's own embedded replay server
            // (or the recorder). When a player joins a normal server that does NOT have Flashback
            // installed, these channels are absent server-side. Marking the registrar optional prevents
            // NeoForge from rejecting the connection with "channel required on client but missing on
            // server" (e.g. Flashback:accurate_entity_position). The channels still work normally during
            // replay, since the embedded replay server registers and sends them.
            PayloadRegistrar registrar = event.registrar("1").optional();

            // Server-to-client payloads (play phase)
            registrar.playToClient(FinishedServerTick.TYPE,
                StreamCodec.unit(FinishedServerTick.INSTANCE),
                (payload, context) -> {});
            registrar.playToClient(FlashbackForceClientTick.TYPE,
                StreamCodec.unit(FlashbackForceClientTick.INSTANCE),
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        Minecraft.getInstance().tick();
                    }
                });
            registrar.playToClient(FlashbackClearParticles.TYPE,
                StreamCodec.unit(FlashbackClearParticles.INSTANCE),
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        Minecraft.getInstance().particleEngine.clearParticles();
                    }
                });
            registrar.playToClient(FlashbackClearEntities.TYPE,
                StreamCodec.unit(FlashbackClearEntities.INSTANCE),
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
                            if (entity != null && !(entity instanceof Player)) {
                                entity.discard();
                            }
                        }
                    }
                });
            registrar.playToClient(FlashbackInstantlyLerp.TYPE,
                StreamCodec.unit(FlashbackInstantlyLerp.INSTANCE),
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
                            if (entity instanceof LivingEntity && !entity.isRemoved() && !(entity instanceof LocalPlayer)) {
                                entity.moveTo(entity.lerpTargetX(), entity.lerpTargetY(), entity.lerpTargetZ(),
                                    entity.lerpTargetYRot(), entity.lerpTargetXRot());
                            }
                            entity.setOldPosAndRot();
                        }
                    }
                });
            registrar.playToClient(FlashbackRemoteSelectHotbarSlot.TYPE,
                FlashbackRemoteSelectHotbarSlot.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        Entity entity = Minecraft.getInstance().level.getEntity(payload.entityId());
                        if (entity instanceof Player player) {
                            player.getInventory().selected = payload.slot();
                        }
                    }
                });
            registrar.playToClient(FlashbackRemoteExperience.TYPE,
                FlashbackRemoteExperience.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        Entity entity = Minecraft.getInstance().level.getEntity(payload.entityId());
                        if (entity instanceof Player player) {
                            player.experienceProgress = payload.experienceProgress();
                            player.totalExperience = payload.totalExperience();
                            player.experienceLevel = payload.experienceLevel();
                        }
                    }
                });
            registrar.playToClient(FlashbackRemoteFoodData.TYPE,
                FlashbackRemoteFoodData.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        Entity entity = Minecraft.getInstance().level.getEntity(payload.entityId());
                        if (entity instanceof Player player) {
                            player.getFoodData().setFoodLevel(payload.foodLevel());
                            player.getFoodData().setSaturation(payload.saturationLevel());
                        }
                    }
                });
            registrar.playToClient(FlashbackRemoteSetSlot.TYPE,
                FlashbackRemoteSetSlot.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        Entity entity = Minecraft.getInstance().level.getEntity(payload.entityId());
                        if (entity instanceof Player player) {
                            player.getInventory().setItem(payload.slot(), payload.itemStack());
                        }
                    }
                });

            if (ModList.get().isLoaded("voicechat")) {
                registrar.playToClient(FlashbackVoiceChatSound.TYPE,
                    FlashbackVoiceChatSound.STREAM_CODEC,
                    (payload, context) -> {
                        if (Flashback.isInReplay()) {
                            SimpleVoiceChatPlayback.play(payload);
                        }
                    });
            }

            registrar.playToClient(FlashbackAccurateEntityPosition.TYPE,
                FlashbackAccurateEntityPosition.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        AccurateEntityPositionHandler.update(payload);
                    }
                });
            registrar.playToClient(FlashbackSetBorderLerpStartTime.TYPE,
                FlashbackSetBorderLerpStartTime.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        worldBorderLerpStartTime = payload.time();
                    }
                });
            registrar.playToClient(FlashbackRawCustomPayload.TYPE,
                FlashbackRawCustomPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (Flashback.isInReplay()) {
                        if (!(context.player() instanceof LocalPlayer localPlayer)) return;
                        var connection = localPlayer.connection;
                        if (connection == null) return;

                        if (connection instanceof ClientCommonPacketListener listener) {
                            var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(payload.packetBytes()), connection.registryAccess());
                            if (payload.configPhase()) {
                                if (listener instanceof ClientConfigurationPacketListener) {
                                    var customPayloadPacket = ClientboundCustomPayloadPacket.CONFIG_STREAM_CODEC.decode(buffer);
                                    customPayloadPacket.handle(listener);
                                }
                            } else if (listener instanceof net.minecraft.client.multiplayer.ClientPacketListener clientListener) {
                                var customPayloadPacket = ClientboundCustomPayloadPacket.GAMEPLAY_STREAM_CODEC.decode(buffer);
                                if (customPayloadPacket.payload() instanceof AdvancedAddEntityPayload aep) {
                                    // The entity might not have been created on the client yet (e.g. the
                                    // AdvancedAddEntityPayload was delivered before the add-entity packet
                                    // during snapshot playback). Buffer it and retry until the entity exists.
                                    // Search the main level AND Aeronautics SubLevels: contraptions on a SubLevel are
                                    // stored in the SubLevel's own Level, so a plain getEntity(id) misses them.
                                    Entity target = findEntityIncludingSubLevels(localPlayer.level(), aep.entityId());
                                    if (target != null) {
                                        if (target.level() == localPlayer.level()) {
                                            customPayloadPacket.handle(clientListener);
                                        } else {
                                            applyComplexSpawnPayload(target, aep.customPayload(), localPlayer);
                                        }
                                        Flashback.refreshContraptionVisual(target);
                                    } else {
                                        pendingAdvancedAddEntityPayloads.add(customPayloadPacket);
                                    }
                                } else {
                                    customPayloadPacket.handle(clientListener);
                                }
                            }
                        }
                    }
                });
        }

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(createMarker1KeyBind);
            event.register(createMarker2KeyBind);
            event.register(createMarker3KeyBind);
            event.register(createMarker4KeyBind);
        }
    }

    @EventBusSubscriber(modid = "flashback_reforged", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void registerClientCommands(RegisterClientCommandsEvent event) {
            var dispatcher = event.getDispatcher();
            var flashback = Commands.literal("flashback");

            flashback.then(Commands.literal("start").executes(commandContext -> {
                startRecordingReplay();
                return 0;
            }));
            flashback.then(Commands.literal("finish").executes(commandContext -> {
                finishRecordingReplay();
                return 0;
            }));
            flashback.then(Commands.literal("end").executes(commandContext -> {
                finishRecordingReplay();
                return 0;
            }));
            flashback.then(Commands.literal("pause").executes(ctx -> {
                pauseRecordingReplay(true);
                return 0;
            }));
            flashback.then(Commands.literal("unpause").executes(ctx -> {
                pauseRecordingReplay(false);
                return 0;
            }));
            flashback.then(Commands.literal("config").executes(commandContext -> {
                delayedOpenConfig = true;
                return 0;
            }));
            flashback.then(Commands.literal("mark")
                .executes(command -> {
                    addMarkerInternal(null, null, null);
                    return 0;
                }).then(Commands.argument("color", BetterColorArgument.color()).executes(command -> {
                    int colour = command.getArgument("color", Integer.class);
                    addMarkerInternal(colour, null, null);
                    return 0;
                }).then(Commands.argument("savePosition", BoolArgumentType.bool()).executes(command -> {
                    int colour = command.getArgument("color", Integer.class);
                    boolean savePosition = BoolArgumentType.getBool(command, "savePosition");
                    addMarkerInternal(colour, savePosition, null);
                    return 0;
                }).then(Commands.argument("description", StringArgumentType.greedyString()).executes(command -> {
                    int colour = command.getArgument("color", Integer.class);
                    boolean savePosition = BoolArgumentType.getBool(command, "savePosition");
                    String description = StringArgumentType.getString(command, "description");
                    addMarkerInternal(colour, savePosition, description);
                    return 0;
                })))));
            dispatcher.register(flashback);
        }
    }

    @EventBusSubscriber(modid = "flashback_reforged", bus = EventBusSubscriber.Bus.GAME)
    public static class GameEvents {
        @SubscribeEvent
        public static void registerCommands(RegisterCommandsEvent event) {
            var dispatcher = event.getDispatcher();

            if (!Flashback.isInReplay() && !isOpeningReplay) {
                return;
            }

            String hideName = "hide";
            if (dispatcher.findNode(Collections.singleton("hide")) != null) {
                hideName = "hide_flashback";
            }
            var hideEntity = Commands.literal(hideName).then(Commands.argument("targets", EntityArgument.entities()).executes(command -> {
                EditorState editorState = EditorStateManager.getCurrent();
                if (!Flashback.isInReplay() || editorState == null) {
                    command.getSource().sendFailure(Component.translatable("flashback.command_only_inside_replay", Component.literal("hide")));
                    return 0;
                }
                var entities = EntityArgument.getEntities(command, "targets");

                for (Entity entity : entities) {
                    editorState.hideDuringExport.add(entity.getUUID());
                }

                int count = entities.size();
                command.getSource().sendSuccess(() -> Component.translatable("flashback.hide_command.n_entities_hidden", Component.literal(String.valueOf(count))), false);
                return 0;
            }));
            dispatcher.register(hideEntity);

            String showName = "show";
            if (dispatcher.findNode(Collections.singleton("show")) != null) {
                showName = "show_flashback";
            }
            var showEntity = Commands.literal(showName).then(Commands.argument("targets", EntityArgument.entities()).executes(command -> {
                EditorState editorState = EditorStateManager.getCurrent();
                if (!Flashback.isInReplay() || editorState == null) {
                    command.getSource().sendFailure(Component.translatable("flashback.command_only_inside_replay", Component.literal("show")));
                    return 0;
                }
                var entities = EntityArgument.getEntities(command, "targets");

                for (Entity entity : entities) {
                    editorState.hideDuringExport.remove(entity.getUUID());
                }

                int count = entities.size();
                command.getSource().sendSuccess(() -> Component.translatable("flashback.show_command.n_entities_shown", Component.literal(String.valueOf(count))), false);
                return 0;
            }));
            dispatcher.register(showEntity);
        }
    }

    // ---- Helper: open new screen on next tick ----

    private static void openNewScreen(AtomicReference<String> unsupportedLoader, Screen currentScreen) {
        if (unsupportedLoader.get() != null) {
            String loaderName = unsupportedLoader.get();
            unsupportedLoader.set(null);
            if (System.currentTimeMillis() > Flashback.getConfig().internal.nextUnsupportedModLoaderWarning) {
                Component warning = Component.translatable("flashback.unsupported_loader.message", Component.literal(loaderName));

                Minecraft.getInstance().setScreen(new UnsupportedLoaderScreen(currentScreen,
                        Component.translatable("flashback.screen_unsupported"), warning));
                return;
            }
        }

        if (!pendingReplayRecovery.isEmpty()) {
            Component nl = FlashbackTextComponents.NEWLINE;
            Component title = Component.translatable("flashback.screen_recovery");
            Component description = Component.empty()
                    .append(Component.translatable("flashback.recovery1", Component.translatable("flashback.recovery2").withStyle(ChatFormatting.YELLOW))).append(nl)
                    .append(Component.translatable("flashback.recovery3")).append(nl).append(nl)
                    .append(Component.translatable("flashback.recovery4").withStyle(ChatFormatting.RED)).append(nl).append(nl)
                    .append(Component.translatable("flashback.recovery5").withStyle(ChatFormatting.GREEN));
            Minecraft.getInstance().setScreen(new RecoverRecordingsScreen(currentScreen, title, description, recover -> {
                switch (recover) {
                    case RECOVER -> {
                        pendingReplaySave.addAll(pendingReplayRecovery);
                        pendingReplayRecovery.clear();
                    }
                    case SKIP -> {
                        pendingReplayRecovery.clear();
                    }
                    case DELETE -> {
                        TempFolderProvider.tryDeleteStaleFolders(TempFolderProvider.TempFolderType.RECORDING);
                        pendingReplayRecovery.clear();
                    }
                }
            }));
            return;
        }

        if (!pendingReplaySave.isEmpty()) {
            Path recordFolder = pendingReplaySave.getFirst();

            LocalDateTime dateTime = LocalDateTime.now();
            dateTime = dateTime.withNano(0);
            Minecraft.getInstance().setScreen(new SaveReplayScreen(currentScreen, recordFolder, dateTime.toString()));
            return;
        }

        if (pendingUnsupportedModsForRecording != null) {
            String mods = StringUtils.join(pendingUnsupportedModsForRecording, ", ");
            Component title = Component.translatable("flashback.incompatible_with_recording");
            Component description = Component.translatable("flashback.incompatible_with_recording_description").append(Component.literal(mods).withStyle(ChatFormatting.RED));
            Minecraft.getInstance().setScreen(new AlertScreen(() -> Minecraft.getInstance().setScreen(currentScreen), title, description));
            pendingUnsupportedModsForRecording = null;
            return;
        }

        if (delayedOpenConfig) {
            openConfigScreen(currentScreen);
            delayedOpenConfig = false;
            return;
        }
    }

    // ---- Utility methods ----

    public static ResourceLocation createResourceLocation(String value) {
        return ResourceLocation.fromNamespaceAndPath("flashback", value);
    }

    public static Path getDataDirectory() {
        return FMLPaths.GAMEDIR.get().resolve("flashback");
    }

    public static Path getReplayFolder() {
        return Flashback.getDataDirectory().resolve("replays");
    }

    public static Path getConfigDirectory() {
        if (configDirectory == null) {
            configDirectory = FMLPaths.CONFIGDIR.get().resolve("flashback");
            try {
                Files.createDirectories(configDirectory);
            } catch (Exception e) {
                LOGGER.error("Unable to create directories for config folder", e);
            }
        }
        return configDirectory;
    }

    public static Screen createConfigScreen(Screen oldScreen) {
        // Lattice config GUI not available on NeoForge with official mappings
        // Return old screen as fallback
        return oldScreen;
    }

    public static void openConfigScreen(Screen oldScreen) {
        Minecraft.getInstance().setScreen(createConfigScreen(oldScreen));
    }

    public static List<String> getReplayIncompatibleMods() {
        List<String> incompatible = new ArrayList<>();
        if (ModList.get().isLoaded("vmp")) {
            incompatible.add("VeryManyPlayers (vmp)");
        }
        if (ModList.get().isLoaded("c2me")) {
            incompatible.add("Concurrent Chunk Management Engine (c2me)");
        }
        return incompatible;
    }

    public static List<String> getRecordingIncompatibleMods() {
        List<String> incompatible = new ArrayList<>();
        if (ModList.get().isLoaded("farsight")) {
            incompatible.add("Farsight");
        }
        if (incompatible.isEmpty()) {
            return null;
        }
        return incompatible;
    }

    private static @Nullable String findUnsupportedLoaders() {
        if (ModList.get().isLoaded("feather")) {
            return "Feather Client";
        } else {
            return null;
        }
    }

    private static boolean canReplaceScreen(Screen screen) {
        return screen == null || screen instanceof PauseScreen || screen instanceof TitleScreen
            || screen instanceof RealmsMainScreen || screen instanceof JoinMultiplayerScreen;
    }

    private static void addMarker(FlashbackConfigV1.SubcategoryMarker.SubcategoryMarkerOptions options) {
        int colour;
        if (options.color == MarkerColour.CUSTOM_RGB) {
            String custom = options.customRGB.replaceAll("[^0-9a-fA-F]", "");
            if (custom.isEmpty()) {
                colour = 0;
            } else {
                colour = Integer.parseInt(custom, 16);
            }
        } else {
            colour = options.color.colour;
        }

        addMarkerInternal(colour, options.savePosition, options.description);
    }

    private static void addMarkerInternal(@Nullable Integer colour, @Nullable Boolean savePosition, @Nullable String description) {
        Minecraft minecraft = Minecraft.getInstance();

        if (RECORDER == null) {
            minecraft.gui.getChat().addMessage(Component.translatable("flashback.mark_command.not_recording").withStyle(ChatFormatting.RED));
            return;
        }

        ReplayMarker.MarkerPosition position = null;
        if (savePosition == null || savePosition) {
            Entity camera = Minecraft.getInstance().getCameraEntity();
            if (camera != null) {
                position = new ReplayMarker.MarkerPosition(camera.getEyePosition().toVector3f(),
                    camera.level().dimension().toString());
            }
        }

        if (description != null && description.isBlank()) {
            description = null;
        }

        String feedback;
        if (description != null) {
            feedback = I18n.get("flashback.mark.added_with_description", description);
        } else if (colour != null) {
            feedback = I18n.get("flashback.mark.added_with_color", Integer.toHexString(colour));
        } else {
            feedback = I18n.get("flashback.mark.added");
        }

        if (position != null) {
            feedback += I18n.get("flashback.mark.added_at", position.position().x, position.position().y, position.position().z);
        }

        if (colour == null) {
            colour = 0xFF5555;
        }

        if (description != null) {
            description = description.trim();
            if (description.isEmpty()) {
                description = null;
            }
        }

        minecraft.gui.getChat().addMessage(Component.literal(feedback));
        RECORDER.addMarker(new ReplayMarker(colour, position, description));
    }

    private void deleteUnusedReplayStates() {
        Path flashbackDir = Flashback.getDataDirectory();
        Path replayDir = Flashback.getReplayFolder();
        Path replayStatesDir = flashbackDir.resolve("editor_states");

        if (!Files.exists(replayDir) || !Files.isDirectory(replayDir)) {
            return;
        }
        if (!Files.exists(replayStatesDir) || !Files.isDirectory(replayStatesDir)) {
            return;
        }

        List<String> recentReplays = new ArrayList<>(Flashback.config.internal.recentReplays);

        CompletableFuture.runAsync(() -> {
            long currentTime = System.currentTimeMillis();
            Map<UUID, Path> replayStates = new HashMap<>();

            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(replayStatesDir)) {
                for (Path path : directoryStream) {
                    String filename = path.getFileName().toString();

                    String withoutExtension = null;

                    if (filename.endsWith(".json")) {
                        withoutExtension = filename.substring(0, filename.length() - 5);
                    } else if (filename.endsWith(".json.old")) {
                        withoutExtension = filename.substring(0, filename.length() - 9);
                    }

                    try {
                        boolean used = false;

                        JsonObject jsonObject = FlashbackGson.COMPRESSED.fromJson(Files.readString(path), JsonObject.class);
                        if (jsonObject.has("usedByPaths")) {
                            for (JsonElement usedBy : jsonObject.get("usedByPaths").getAsJsonArray()) {
                                String usedByStr = usedBy.getAsString();
                                if (recentReplays.contains(usedByStr)) {
                                    used = true;
                                    break;
                                }

                                Path usedByPath = Path.of(usedBy.getAsString());
                                if (Files.exists(usedByPath)) {
                                    used = true;
                                    break;
                                }
                            }
                        }

                        if (used) {
                            continue;
                        }
                    } catch (Exception ignored) {}

                    BasicFileAttributeView attributeView = Files.getFileAttributeView(path, BasicFileAttributeView.class);
                    BasicFileAttributes basicFileAttributes = attributeView.readAttributes();

                    long lastModified = Math.max(basicFileAttributes.creationTime().toMillis(), basicFileAttributes.lastModifiedTime().toMillis());
                    long timeDifference = Math.abs(currentTime - lastModified);
                    if (timeDifference < Duration.ofDays(30).toMillis()) {
                        continue;
                    }

                    if (withoutExtension != null) {
                        UUID uuid;
                        try {
                            uuid = UUID.fromString(withoutExtension);
                        } catch (Exception ignored) {
                            continue;
                        }

                        replayStates.put(uuid, path);
                    }
                }
            } catch (IOException ignored) {}

            if (replayStates.isEmpty()) {
                return;
            }

            Set<UUID> replayUuids = new HashSet<>();
            Set<Path> checkedReplayPaths = new HashSet<>();

            try {
                for (String recentReplayStr : recentReplays) {
                    Path path = Path.of(recentReplayStr);
                    if (!checkedReplayPaths.add(path)) {
                        continue;
                    }
                    if (!Files.exists(path)) {
                        continue;
                    }
                    readReplayUuidIntoSet(path, replayUuids);
                }
            } catch (IOException e) {
                Flashback.LOGGER.error("Unable read replay uuid", e);
                return;
            }

            try (DirectoryStream<Path> directoryStream = Files.newDirectoryStream(replayDir)) {
                for (Path path : directoryStream) {
                    if (!checkedReplayPaths.add(path)) {
                        continue;
                    }
                    readReplayUuidIntoSet(path, replayUuids);
                }
            } catch (IOException e) {
                Flashback.LOGGER.error("Unable to iterate replay directory or read replay uuid", e);
                return;
            }

            for (Map.Entry<UUID, Path> entry : replayStates.entrySet()) {
                if (!replayUuids.contains(entry.getKey())) {
                    try {
                        Files.deleteIfExists(entry.getValue());
                    } catch (IOException ignored) {}
                }
            }
        }, Util.backgroundExecutor());
    }

    private static void readReplayUuidIntoSet(Path path, Set<UUID> replayUuids) throws IOException {
        if (!path.toString().endsWith(".zip")) {
            return;
        }

        String metadataString = null;

        try (FileSystem fs = FileSystems.newFileSystem(path)) {
            Path metadataPath = fs.getPath("/metadata.json");
            if (Files.exists(metadataPath)) {
                metadataString = Files.readString(metadataPath);
            }
        }

        if (metadataString != null) {
            JsonObject metadataJson = new Gson().fromJson(metadataString, JsonObject.class);
            FlashbackMeta metadata = FlashbackMeta.fromJson(metadataJson);
            if (metadata != null) {
                replayUuids.add(metadata.replayIdentifier);
            }
        }
    }

    // ---- Public API ----

    public static FlashbackConfigV1 getConfig() {
        return config;
    }

    @Nullable
    public static ReplayServer getReplayServer() {
        if (Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer replayServer) {
            return replayServer;
        }
        return null;
    }

    public static void removePendingReplaySave(Path recordFolder) {
        pendingReplaySave.remove(recordFolder);
    }

    public static boolean isExporting() {
        return EXPORT_JOB != null && EXPORT_JOB.isRunning();
    }

    public static void updateIsInReplay() {
        isInReplay = Minecraft.getInstance().getSingleplayerServer() instanceof ReplayServer;
    }

    public static boolean isInReplay() {
        return isInReplay || isOpeningReplay;
    }

    public static long getVisualMillis() {
        ReplayServer replayServer = Flashback.getReplayServer();
        if (replayServer != null) {
            float tick;

            ExportJob exportJob = Flashback.EXPORT_JOB;
            if (exportJob != null) {
                tick = (float) exportJob.getCurrentTickDouble();
            } else {
                tick = (float) replayServer.getPartialReplayTick();
            }

            return (long)(tick * 50L);
        } else {
            return Util.getMillis();
        }
    }

    public static void startRecordingReplay() {
        if (RECORDER != null) {
            SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.RECORDING_TOAST,
                    Component.literal("Already Recording"), Component.literal("Cannot start new recording when already recording"));
            return;
        }

        List<String> unsupported = getRecordingIncompatibleMods();
        if (unsupported != null && !unsupported.isEmpty()) {
            pendingUnsupportedModsForRecording = unsupported;
            return;
        }

        RECORDER = new Recorder(Minecraft.getInstance().player.registryAccess());
        if (Flashback.getConfig().recordingControls.showRecordingToasts) {
            SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.RECORDING_TOAST,
                    FlashbackTextComponents.FLASHBACK, Component.translatable("flashback.toast.started_recording"));
        }
    }

    public static void pauseRecordingReplay(boolean pause) {
        if (RECORDER != null) {
            RECORDER.setPaused(pause);
        }

        if (Flashback.getConfig().recordingControls.showRecordingToasts) {
            SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.RECORDING_TOAST,
                    FlashbackTextComponents.FLASHBACK, Component.translatable(pause ? "flashback.toast.paused_recording" : "flashback.toast.unpaused_recording"));
        }
    }

    public static void cancelRecordingReplay() {
        Recorder recorder = RECORDER;
        RECORDER = null;

        Path recordFolder = recorder.finish();
        try {
            FileUtils.deleteDirectory(recordFolder.toFile());
        } catch (Exception e) {
            Flashback.LOGGER.error("Exception deleting record folder", e);
        }

        if (Flashback.getConfig().recordingControls.showRecordingToasts) {
            SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.RECORDING_TOAST,
                FlashbackTextComponents.FLASHBACK, Component.translatable("flashback.toast.cancelled_recording"));
        }
    }

    public static void finishRecordingReplay() {
        if (RECORDER == null) {
            SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.RECORDING_TOAST,
                    Component.translatable("flashback.toast.not_recording"), Component.translatable("flashback.toast.cant_finish_when_not_recording"));
            return;
        }

        Recorder recorder = RECORDER;
        RECORDER = null;
        recorder.endTick(true);

        if (Flashback.getConfig().recordingControls.quicksave) {
            Path replayDir = getReplayFolder();

            if (!Files.exists(replayDir)) {
                try {
                    Files.createDirectories(replayDir);
                } catch (IOException ignored) {}
            }

            String filename;
            try {
                LocalDateTime dateTime = LocalDateTime.now();
                dateTime = dateTime.withNano(0);
                filename = FileUtil.findAvailableName(replayDir, dateTime.toString(), ".zip");
            } catch (IOException e) {
                Flashback.LOGGER.error("Error while trying to determine filename", e);
                filename = UUID.randomUUID() + ".zip";
            }

            Path outputFile = replayDir.resolve(filename);
            ReplayExporter.export(recorder.finish(), outputFile, null);
        } else {
            pendingReplaySave.add(recorder.finish());
        }

        if (Flashback.getConfig().recordingControls.showRecordingToasts) {
            SystemToast.add(Minecraft.getInstance().getToasts(), FlashbackSystemToasts.RECORDING_TOAST,
                FlashbackTextComponents.FLASHBACK, Component.translatable("flashback.toast.finished_recording"));
        }
    }

    @Nullable
    public static AbstractClientPlayer getSpectatingPlayer() {
        if (!isInReplay()) {
            return null;
        }
        if (Minecraft.getInstance().getCameraEntity() instanceof AbstractClientPlayer clientPlayer) {
            if (clientPlayer != Minecraft.getInstance().player) {
                return clientPlayer;
            }
        }
        return null;
    }

    public static void openReplayFromFileBrowser() {
        String defaultFolder = Flashback.getReplayFolder().toString();
        AsyncFileDialogs.openFileDialog(defaultFolder, "Zip File", "zip").thenAccept(pathStr -> {
            if (pathStr != null) {
                Path path = Path.of(pathStr);
                Minecraft.getInstance().submit(() -> {
                    Flashback.openReplayWorld(path);
                });
            }
        });
    }

    public static GameRules createReplayGameRules() {
        GameRules gameRules = new GameRules();
        gameRules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        gameRules.getRule(GameRules.RULE_DOENTITYDROPS).set(false, null);
        gameRules.getRule(GameRules.RULE_ANNOUNCE_ADVANCEMENTS).set(false, null);
        gameRules.getRule(GameRules.RULE_DISABLE_RAIDS).set(true, null);
        gameRules.getRule(GameRules.RULE_DO_PATROL_SPAWNING).set(false, null);
        gameRules.getRule(GameRules.RULE_DO_WARDEN_SPAWNING).set(false, null);
        gameRules.getRule(GameRules.RULE_DO_TRADER_SPAWNING).set(false, null);
        gameRules.getRule(GameRules.RULE_DO_VINES_SPREAD).set(false, null);
        gameRules.getRule(GameRules.RULE_DOFIRETICK).set(false, null);
        gameRules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        gameRules.getRule(GameRules.RULE_RANDOMTICKING).set(0, null);
        return gameRules;
    }

    // The live client's registry access, captured right before a replay world is opened. The embedded
    // replay server uses this to fill in any registry entries that are missing from its own registry
    // (e.g. the minecraft:rhombus banner pattern), which would otherwise cause "Can't find id" errors
    // and disconnect the viewer during playback.
    public static net.minecraft.core.RegistryAccess clientRegistryAccess = null;

    // Debug helpers for the minecraft:rhombus banner pattern issue. Avoid Registries.Banner_PATTERN
    // (field name differs across mappings) and ResourceLocation constructors (private in this version).
    public static net.minecraft.core.Registry<?> getBannerPatternRegistry(net.minecraft.core.RegistryAccess access) {
        if (access == null) return null;
        for (var e : access.registries().toList()) {
            if (e.key().location().toString().equals("minecraft:banner_pattern")) {
                return e.value();
            }
        }
        return null;
    }

    public static boolean registryHasRhombus(net.minecraft.core.Registry<?> reg) {
        if (reg == null) return false;
        for (var k : reg.registryKeySet()) {
            if (k.location().getNamespace().equals("minecraft") && k.location().getPath().equals("rhombus")) return true;
        }
        return false;
    }

    public static void openReplayWorld(Path path) {
        Minecraft minecraft = Minecraft.getInstance();
        // Capture the live client's registry access if we are currently connected to a (real) world.
        // If we are not connected (e.g. opening a replay from the title screen), keep the cached value
        // from the last world we were in, since it still contains registry entries such as the
        // minecraft:rhombus banner pattern that the embedded replay server is otherwise missing.
        if (minecraft.getConnection() != null) {
            Flashback.clientRegistryAccess = minecraft.getConnection().registryAccess();
        }
        if (Flashback.clientRegistryAccess != null) {
            var bp = Flashback.getBannerPatternRegistry(Flashback.clientRegistryAccess);
        } else {
            Flashback.LOGGER.warn("[Flashback] openReplayWorld: clientRegistryAccess is NULL (no cached registry from a previous world)");
        }
        if (minecraft.level != null) {
            minecraft.level.disconnect();
        }
        minecraft.disconnect();
        minecraft.setScreen(new TitleScreen());

        ReplayUI.shownRegistryErrorWarning = false;
        ReplayUI.shownPlayerSpawnErrorWarning = false;

        String pathStr = path.toString();
        FlashbackConfigV1 config = Flashback.getConfig();
        config.internal.recentReplays.remove(pathStr);
        config.internal.recentReplays.add(0, pathStr);
        if (config.internal.recentReplays.size() > 32) {
            config.internal.recentReplays.remove(config.internal.recentReplays.size() - 1);
        }
        config.delayedSaveToDefaultFolder();

        try {
            isOpeningReplay = true;

            UUID replayUuid = UUID.randomUUID();
            Path replayTemp = TempFolderProvider.createTemp(TempFolderProvider.TempFolderType.SERVER, replayUuid);
            FileUtils.deleteDirectory(replayTemp.toFile());

            LevelStorageSource source = new LevelStorageSource(replayTemp.resolve("saves"), replayTemp.resolve("backups"),
                Minecraft.getInstance().directoryValidator(), Minecraft.getInstance().getFixerUpper());
            LevelStorageSource.LevelStorageAccess access = source.createAccess("replay");
            PackRepository packRepository = ServerPacksSource.createPackRepository(access);

            packRepository.reload();

            // Use the data packs that are currently enabled in the client. The replay was recorded
            // in a world that had these packs enabled (e.g. the Create Aeronautics pack adds custom
            // registry content such as the minecraft:rhombus banner pattern via a datapack). Without
            // them, the embedded replay server's registry is missing those entries, and network/payload
            // synchronization during player placement throws "Can't find id for '<entry>'" which closes
            // the connection ("切断されました"). Flashback still overrides the registry with the recorded
            // KnownRegistryData afterwards, so this just provides a correct baseline matching the
            // environment the replay was captured in.
            Set<String> availableServerPacks = packRepository.getAvailablePacks().stream()
                .map(Pack::getId)
                .collect(java.util.stream.Collectors.toSet());
            var clientRepository = Minecraft.getInstance().getResourcePackRepository();
            List<String> selectedDataPacks = clientRepository.getSelectedIds().stream()
                .filter(availableServerPacks::contains)
                .collect(java.util.stream.Collectors.toList());

            GameRules gameRules = createReplayGameRules();

            WorldDataConfiguration worldDataConfiguration = new WorldDataConfiguration(new DataPackConfig(selectedDataPacks, List.of()), FeatureFlags.DEFAULT_FLAGS);
            LevelSettings levelSettings = new LevelSettings("Replay", GameType.SPECTATOR, false, Difficulty.NORMAL, true, gameRules, worldDataConfiguration);
            WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(packRepository, worldDataConfiguration, false, true);
            WorldLoader.InitConfig initConfig = new WorldLoader.InitConfig(packConfig, Commands.CommandSelection.DEDICATED, 4);

            WorldStem worldStem = Util.blockUntilDone(executor -> WorldLoader.load(initConfig, dataLoadContext -> {
                Registry<LevelStem> registry = new MappedRegistry<>(Registries.LEVEL_STEM, Lifecycle.stable()).freeze();

                Holder.Reference<Biome> plains = dataLoadContext.datapackWorldgen().registryOrThrow(Registries.BIOME).getHolder(Biomes.PLAINS).get();
                Holder.Reference<DimensionType> overworld = dataLoadContext.datapackWorldgen().registryOrThrow(Registries.DIMENSION_TYPE).getHolder(BuiltinDimensionTypes.OVERWORLD).get();

                WorldDimensions worldDimensions = new WorldDimensions(Map.of(LevelStem.OVERWORLD, new LevelStem(overworld, new EmptyLevelSource(plains))));
                WorldDimensions.Complete complete = worldDimensions.bake(registry);

                return new WorldLoader.DataLoadOutput<>(new PrimaryLevelData(levelSettings, new WorldOptions(0L, false, false),
                    complete.specialWorldProperty(), complete.lifecycle()), complete.dimensionsRegistryAccess());
            }, WorldStem::new, Util.backgroundExecutor(), executor)).get();

            ((MinecraftExt)Minecraft.getInstance()).flashback$startReplayServer(access, packRepository, worldStem, new MinecraftExt.StartReplayServerInfo(replayUuid, path));

            TaskbarManager.launchTaskbarManager();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            isOpeningReplay = false;
        }
    }
}
