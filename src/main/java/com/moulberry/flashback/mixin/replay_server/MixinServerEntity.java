package com.moulberry.flashback.mixin.replay_server;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.bundle.PacketAndPayloadAcceptor;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Mixin(ServerEntity.class)
public class MixinServerEntity {

    /*
     * Force update interval to be 1 on a replay server, sending updates as soon as possible
     */

    @Shadow public List<SynchedEntityData.DataValue<?>> trackedDataValues;

    @Shadow public Entity entity;

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static int init_modifyUpdateInterval(int updateInterval, @Local(argsOnly = true) ServerLevel level) {
        if (updateInterval < 20 && level != null && level.getServer() instanceof ReplayServer) {
            return 1;
        }
        return updateInterval;
    }

    /*
     * Fix a bug where hand animations will be wrong due to incorrect packet order
     */
    @WrapOperation(method = "addPairing", at = @At(value = "INVOKE", ordinal = 3))
    public void addPairing_sendPairingData(ServerEntity instance, ServerPlayer serverPlayer, PacketAndPayloadAcceptor acceptor, Operation<Void> original) {
        if (Flashback.isInReplay()) {
            List<ClientboundSetEntityDataPacket> delayed = new ArrayList<>();

            Consumer<Packet> delayedConsumer = (Packet packet) -> {
                if (packet instanceof ClientboundSetEntityDataPacket setEntityDataPacket) {
                    delayed.add(setEntityDataPacket);
                } else {
                    acceptor.accept(packet);
                }
            };

            original.call(instance, serverPlayer, new PacketAndPayloadAcceptor(delayedConsumer));

            for (ClientboundSetEntityDataPacket setEntityDataPacket : delayed) {
                acceptor.accept(setEntityDataPacket);
            }
        } else {
            original.call(instance, serverPlayer, acceptor);
        }
    }

    /*
     * For entities implementing NeoForge's IEntityWithComplexSpawn (e.g. Create contraptions), the
     * server normally re-serializes spawn data via an AdvancedAddEntityPayload when pairing the
     * entity to a viewer. Our replay server recreates these entities from the add-entity packet and
     * never has that spawn data applied, so re-serializing it would NPE (Create's controllerPos is
     * null). We therefore skip the *entire* pairing for complex entities on a replay server.
     * Flashback instead forwards the ClientboundAddEntityPacket to the client directly from
     * ReplayGamePacketHandler.handleAddEntity, and the complex spawn data (the contraption's block
     * structure) is delivered via the AdvancedAddEntityPayload captured in the snapshot and applied
     * by Flashback's client-side retry loop. Skipping the whole pairing also avoids a duplicate
     * AddEntityPacket reaching the client.
     */
    @Inject(method = "sendPairingData(Lnet/minecraft/server/level/ServerPlayer;Lnet/neoforged/neoforge/network/bundle/PacketAndPayloadAcceptor;)V", at = @At("HEAD"), cancellable = true)
    public void sendPairingData_skipComplexSpawn(ServerPlayer serverPlayer, PacketAndPayloadAcceptor acceptor, CallbackInfo ci) {
        if (Flashback.isInReplay() && this.entity instanceof IEntityWithComplexSpawn) {
            ci.cancel();
        }
    }

}
