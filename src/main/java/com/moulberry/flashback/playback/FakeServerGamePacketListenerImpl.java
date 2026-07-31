package com.moulberry.flashback.playback;

import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.jetbrains.annotations.Nullable;

/**
 * A fake network handler for replay server fake players.
 * Equivalent to Fabric's FakePlayerNetworkHandler.
 */
public class FakeServerGamePacketListenerImpl extends ServerGamePacketListenerImpl {

    public FakeServerGamePacketListenerImpl(ServerPlayer serverPlayer) {
        super(serverPlayer.server, ReplayConnectionUtil.createReplayConnection(),
            serverPlayer, CommonListenerCookie.createInitial(serverPlayer.getGameProfile(), false));
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener packetSendListener) {
    }

    @Override
    public boolean isAcceptingMessages() {
        return false;
    }
}
