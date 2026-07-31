package com.moulberry.flashback.playback;

import com.moulberry.flashback.Flashback;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.jetbrains.annotations.Nullable;

/**
 * Creates a {@link Connection} suitable for replay-playback fake/recorded players.
 *
 * <p>NeoForge's datapack sync ({@code NeoForgeEventHandler.onDpSync}) requires a non-null
 * {@link Connection#channel()} to register network channels. A plain {@link Connection} built
 * without a real socket has a {@code null} channel, which crashes player spawning with an NPE.
 * We therefore back the connection with an {@link EmbeddedChannel} and override {@code channel()}
 * so that it always returns it (an overridden method cannot be reset by other code).</p>
 */
public final class ReplayConnectionUtil {

    private ReplayConnectionUtil() {}

    public static Connection createReplayConnection() {
        final Channel[] channelHolder = new Channel[1];
        Connection connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet, @Nullable PacketSendListener packetSendListener, boolean bl) {
            }
            @Override
            public Channel channel() {
                return channelHolder[0];
            }
        };
        EmbeddedChannel embeddedChannel = new EmbeddedChannel(connection);
        channelHolder[0] = embeddedChannel;
        // Also set the private channel field (found by type to avoid obfuscated-name issues)
        // so field-based accessors like isMemoryConnection() behave correctly.
        try {
            for (java.lang.reflect.Field f : Connection.class.getDeclaredFields()) {
                if (f.getType().equals(Channel.class)) {
                    f.setAccessible(true);
                    f.set(connection, embeddedChannel);
                    break;
                }
            }
        } catch (ReflectiveOperationException e) {
            Flashback.LOGGER.error("Failed to set channel field on Connection", e);
        }
        return connection;
    }
}
