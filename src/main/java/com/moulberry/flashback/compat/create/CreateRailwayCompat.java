package com.moulberry.flashback.compat.create;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Captures Create's railway state (trains + track graphs) into a Flashback snapshot.
 *
 * Create trains and track graphs are NOT Minecraft entities - they live in Create's
 * {@code RAILWAYS} manager and are synced to the client via custom payloads (AddTrainPacket,
 * TrackGraphSyncPacket, ...). Flashback's snapshot/scrub system only restores entities, so
 * after scrubbing to a snapshot a train's carriage entity is restored but the train object
 * (and track graph) it depends on is not, leaving the carriage unable to align/move.
 *
 * To fix this we, at snapshot-creation time, read the client's Create railway state and emit
 * the same packets Create would send on player login (AddTrainPacket for each train, and a
 * full-wipe TrackGraphSyncPacket for each track graph). These are replayed when the snapshot
 * is loaded, re-creating the trains and track graphs exactly as they were.
 */
public class CreateRailwayCompat {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void captureRailwaySnapshot(Consumer<Packet<? super ClientGamePacketListener>> consumer) {
        try {
            captureRailwaySnapshot0(consumer);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Flashback: failed to capture Create railway snapshot state: {}", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void captureRailwaySnapshot0(Consumer<Packet<? super ClientGamePacketListener>> consumer) throws Exception {
        Class<?> createClientClass = Class.forName("com.simibubi.create.CreateClient");
        Field railwaysField = createClientClass.getField("RAILWAYS");
        Object railways = railwaysField.get(null);
        if (railways == null) return;

        Field trainsField = railways.getClass().getField("trains");
        Field graphsField = railways.getClass().getField("trackNetworks");
        Map<UUID, Object> trains = (Map<UUID, Object>) trainsField.get(railways);
        Map<UUID, Object> graphs = (Map<UUID, Object>) graphsField.get(railways);

        // Debug: uncomment to log how many graphs/trains are captured into the snapshot
        // Flashback.LOGGER.info("[Flashback/Create] snapshot capture: {} track graphs, {} trains",
        //     graphs == null ? 0 : graphs.size(), trains == null ? 0 : trains.size());

        // --- Track graphs first (same order as Create's playerLogin):
        //     full-wipe TrackGraphSyncPacket for each graph ---
        Class<?> trackGraphClass = Class.forName("com.simibubi.create.content.trains.graph.TrackGraph");
        Class<?> tgspClass = Class.forName("com.simibubi.create.content.trains.graph.TrackGraphSyncPacket");
        Constructor<?> tgspCtor = tgspClass.getConstructor(UUID.class, int.class);

        Class<?> pairClass = Class.forName("net.createmod.catnip.data.Pair");
        Method pairOf = pairClass.getMethod("of", Object.class, Object.class);
        Class<?> coupleClass = Class.forName("net.createmod.catnip.data.Couple");
        Method coupleCreate = coupleClass.getMethod("create", Object.class, Object.class);

        Field graphIdField = trackGraphClass.getField("id");
        Field graphNetIdField = trackGraphClass.getDeclaredField("netId");
        graphNetIdField.setAccessible(true);
        Field nodesField = trackGraphClass.getDeclaredField("nodes");
        nodesField.setAccessible(true);
        Field connectionsField = trackGraphClass.getDeclaredField("connectionsByNode");
        connectionsField.setAccessible(true);

        Field fullWipeField = tgspClass.getDeclaredField("fullWipe");
        fullWipeField.setAccessible(true);
        Field addedNodesField = tgspClass.getDeclaredField("addedNodes");
        addedNodesField.setAccessible(true);
        Field addedEdgesField = tgspClass.getDeclaredField("addedEdges");
        addedEdgesField.setAccessible(true);
        Field addedEdgePointsField = tgspClass.getDeclaredField("addedEdgePoints");
        addedEdgePointsField.setAccessible(true);

        Method nodeGetNetId = Class.forName("com.simibubi.create.content.trains.graph.TrackNode").getMethod("getNetId");
        Method nodeGetLocation = Class.forName("com.simibubi.create.content.trains.graph.TrackNode").getMethod("getLocation");
        Method nodeGetNormal = Class.forName("com.simibubi.create.content.trains.graph.TrackNode").getMethod("getNormal");
        Method edgeGetMaterial = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge").getMethod("getTrackMaterial");
        Method edgeGetTurn = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge").getMethod("getTurn");
        Method edgeGetEdgeData = Class.forName("com.simibubi.create.content.trains.graph.TrackEdge").getMethod("getEdgeData");
        Method edgeDataGetPoints = Class.forName("com.simibubi.create.content.trains.graph.EdgeData").getMethod("getPoints");

        if (graphs != null) {
            for (Object graph : graphs.values()) {
                if (graph == null) continue;
                UUID graphId = (UUID) graphIdField.get(graph);
                int netId = graphNetIdField.getInt(graph);

                Object packet = tgspCtor.newInstance(graphId, netId);
                fullWipeField.setBoolean(packet, true);

                Map<Integer, Object> addedNodes = (Map<Integer, Object>) addedNodesField.get(packet);
                Collection<Object> addedEdges = (Collection<Object>) addedEdgesField.get(packet);
                Collection<Object> addedEdgePoints = (Collection<Object>) addedEdgePointsField.get(packet);

                Map<Object, Object> nodes = (Map<Object, Object>) nodesField.get(graph);
                if (nodes != null) {
                    for (Object node : nodes.values()) {
                        int nodeNetId = (int) nodeGetNetId.invoke(node);
                        Object loc = nodeGetLocation.invoke(node);
                        Object normal = nodeGetNormal.invoke(node);
                        addedNodes.put(nodeNetId, pairOf.invoke(null, loc, normal));
                    }
                }

                Map<Object, Map<Object, Object>> connections = (Map<Object, Map<Object, Object>>) connectionsField.get(graph);
                Set<Object> sentPoints = new HashSet<>();
                if (connections != null) {
                    for (Map.Entry<Object, Map<Object, Object>> e : connections.entrySet()) {
                        Object node1 = e.getKey();
                        int n1 = (int) nodeGetNetId.invoke(node1);
                        for (Map.Entry<Object, Object> e2 : e.getValue().entrySet()) {
                            Object node2 = e2.getKey();
                            Object edge = e2.getValue();
                            int n2 = (int) nodeGetNetId.invoke(node2);
                            Object material = edgeGetMaterial.invoke(edge);
                            Object turn = edgeGetTurn.invoke(edge);

                            Object key = coupleCreate.invoke(null, n1, n2);
                            Object innerPair = pairOf.invoke(null, key, material);
                            Object edgeEntry = pairOf.invoke(null, innerPair, turn);
                            addedEdges.add(edgeEntry);

                            Object edgeData = edgeGetEdgeData.invoke(edge);
                            Collection<Object> points = (Collection<Object>) edgeDataGetPoints.invoke(edgeData);
                            if (points != null) {
                                for (Object p : points) {
                                    if (sentPoints.add(p)) {
                                        addedEdgePoints.add(p);
                                    }
                                }
                            }
                        }
                    }
                }

                // Also include graph-level edge points (stations, signals, ...)
                try {
                    Class<?> eptClass = Class.forName("com.simibubi.create.content.trains.graph.EdgePointType");
                    Map<?, ?> types = (Map<?, ?>) eptClass.getField("TYPES").get(null);
                    Method getPoints = trackGraphClass.getMethod("getPoints", eptClass);
                    if (types != null) {
                        for (Object type : types.values()) {
                            Collection<Object> pts = (Collection<Object>) getPoints.invoke(graph, type);
                            if (pts != null) {
                                for (Object p : pts) {
                                    if (sentPoints.add(p)) {
                                        addedEdgePoints.add(p);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Edge points are non-essential for basic train movement; skip if unavailable
                }

                consumer.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) packet));
            }
        }

        // --- Trains: AddTrainPacket(train) for each train (after graphs exist) ---
        Class<?> trainClass = Class.forName("com.simibubi.create.content.trains.entity.Train");
        Class<?> addTrainPacketClass = Class.forName("com.simibubi.create.content.trains.entity.AddTrainPacket");
        Constructor<?> addTrainCtor = addTrainPacketClass.getConstructor(trainClass);
        if (trains != null) {
            for (Object train : trains.values()) {
                if (train == null) continue;
                Object packet = addTrainCtor.newInstance(train);
                consumer.accept(new ClientboundCustomPayloadPacket((CustomPacketPayload) packet));
            }
        }
    }

    /**
     * Emits an extra ClientboundSetEntityDataPacket (TRACK_GRAPH, then CARRIAGE_DATA) for every
     * Create carriage entity, to be placed in the snapshot AFTER the entity spawn/data packets.
     *
     * Why this is needed: a train's position on the track is only synced through the
     * CARRIAGE_DATA entity data value (wheel locations); {@code Train}'s network codec does not
     * include it. When the carriage entity's initial data packet is applied, Create processes
     * CARRIAGE_DATA before TRACK_GRAPH (definition order), so {@code train.graph} is still null
     * and the wheel positions are silently discarded. On a live server this self-heals because
     * CARRIAGE_DATA is re-sent every few ticks, but in a snapshot it never would - so after
     * rewinding, trains snap back to their spawn position with glitched bogeys. Re-sending
     * TRACK_GRAPH followed by CARRIAGE_DATA after the entity exists restores the exact position.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void captureCarriageDataResend(Consumer<Packet<? super ClientGamePacketListener>> consumer) {
        try {
            captureCarriageDataResend0(consumer);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Flashback: failed to capture Create carriage data for snapshot: {}", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void captureCarriageDataResend0(Consumer<Packet<? super ClientGamePacketListener>> consumer) throws Exception {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        Class<?> cceClass = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");

        Field carriageDataField = cceClass.getDeclaredField("CARRIAGE_DATA");
        carriageDataField.setAccessible(true);
        EntityDataAccessor<Object> carriageDataAccessor = (EntityDataAccessor<Object>) carriageDataField.get(null);

        Field trackGraphField = cceClass.getDeclaredField("TRACK_GRAPH");
        trackGraphField.setAccessible(true);
        EntityDataAccessor<Object> trackGraphAccessor = (EntityDataAccessor<Object>) trackGraphField.get(null);

        Method copyMethod = Class.forName("com.simibubi.create.content.trains.entity.CarriageSyncData").getMethod("copy");

        for (Entity entity : level.entitiesForRendering()) {
            if (!cceClass.isInstance(entity)) continue;

            List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(2);

            // TRACK_GRAPH first so train.graph is bound before CARRIAGE_DATA is applied
            Object trackGraph = entity.getEntityData().get(trackGraphAccessor);
            values.add(SynchedEntityData.DataValue.create(trackGraphAccessor, trackGraph));

            Object carriageData = entity.getEntityData().get(carriageDataAccessor);
            if (carriageData != null) {
                // Copy: the packet is serialized asynchronously, the live object may mutate
                values.add(SynchedEntityData.DataValue.create(carriageDataAccessor, copyMethod.invoke(carriageData)));
            }

            consumer.accept(new ClientboundSetEntityDataPacket(entity.getId(), values));
        }
    }

    /**
     * Called on the replay server AFTER a snapshot was applied and the viewers' entity
     * tracking was refreshed (chunkSource.move). Sends, for every Create carriage entity
     * that exists on the fake server, a ClientboundSetEntityDataPacket containing
     * TRACK_GRAPH followed by CARRIAGE_DATA directly to every replay viewer.
     *
     * Why this can't be done inside the snapshot itself: while a snapshot is being
     * processed, entity data packets are only forwarded via chunk-tracking broadcast,
     * but the viewer does not track the freshly respawned entities until the tracking
     * refresh that happens after the snapshot completes. So any resend embedded in the
     * snapshot silently reaches nobody. The initial pairing data the viewer does receive
     * applies CARRIAGE_DATA before TRACK_GRAPH (data id order), which Create discards
     * (train.graph still null) - losing the wheel positions. Sending this packet after
     * tracking is established restores the exact wheel positions on the client.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void resendCarriageDataAfterSnapshot(Iterable<ServerLevel> levels, Collection<? extends ServerPlayer> viewers) {
        try {
            resendCarriageDataAfterSnapshot0(levels, viewers);
        } catch (Throwable t) {
            Flashback.LOGGER.warn("Flashback: failed to resend Create carriage data after snapshot: {}", t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void resendCarriageDataAfterSnapshot0(Iterable<ServerLevel> levels, Collection<? extends ServerPlayer> viewers) throws Exception {
        if (viewers.isEmpty()) return;

        Class<?> cceClass;
        try {
            cceClass = Class.forName("com.simibubi.create.content.trains.entity.CarriageContraptionEntity");
        } catch (ClassNotFoundException e) {
            return; // Create not installed
        }

        Field carriageDataField = cceClass.getDeclaredField("CARRIAGE_DATA");
        carriageDataField.setAccessible(true);
        EntityDataAccessor<Object> carriageDataAccessor = (EntityDataAccessor<Object>) carriageDataField.get(null);

        Field trackGraphField = cceClass.getDeclaredField("TRACK_GRAPH");
        trackGraphField.setAccessible(true);
        EntityDataAccessor<Object> trackGraphAccessor = (EntityDataAccessor<Object>) trackGraphField.get(null);

        Class<?> syncDataClass = Class.forName("com.simibubi.create.content.trains.entity.CarriageSyncData");
        Method copyMethod = syncDataClass.getMethod("copy");

        int carriageCount = 0;
        for (ServerLevel level : levels) {
            for (Entity entity : level.getAllEntities()) {
                if (!cceClass.isInstance(entity)) continue;

                List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(2);

                // TRACK_GRAPH first so train.graph is bound before CARRIAGE_DATA is applied
                Object trackGraph = entity.getEntityData().get(trackGraphAccessor);
                values.add(SynchedEntityData.DataValue.create(trackGraphAccessor, trackGraph));

                Object carriageData = entity.getEntityData().get(carriageDataAccessor);
                if (carriageData != null) {
                    Object copied = copyMethod.invoke(carriageData);
                    values.add(SynchedEntityData.DataValue.create(carriageDataAccessor, copied));
                }

                ClientboundSetEntityDataPacket packet = new ClientboundSetEntityDataPacket(entity.getId(), values);
                for (ServerPlayer viewer : viewers) {
                    viewer.connection.send(packet);
                }
                carriageCount++;
            }
        }

        // Debug: uncomment to log the post-snapshot carriage data resync
        // Flashback.LOGGER.info("[Flashback/Create] resent carriage data for {} carriages to {} viewers after snapshot",
        //     carriageCount, viewers.size());
    }
}
