package com.moulberry.flashback.mixin.record;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * NeoForge's BundlerInfo.unbundlePacket can filter out ALL sub-packets of a
 * ClientboundBundlePacket (e.g. when the replay connection's network channels
 * are not registered on the replay registry). When that happens the output list
 * stays empty, and MessageToMessageEncoder.write throws
 * "PacketBundleUnpacker must produce at least one message", disconnecting the
 * client. This redirect makes an empty unbundle pass the original bundle packet
 * through untouched instead of producing nothing (and thus crashing).
 */
@Mixin(PacketBundleUnpacker.class)
public abstract class MixinPacketBundleUnpacker extends MessageToMessageEncoder<Packet<?>> {

    @Redirect(
        method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Ljava/util/List;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/protocol/BundlerInfo;unbundlePacket(Lnet/minecraft/network/protocol/Packet;Ljava/util/function/Consumer;Lio/netty/channel/ChannelHandlerContext;)V"
        )
    )
    private void flashback$unbundlePacketSafe(net.minecraft.network.protocol.BundlerInfo instance,
                                              Packet<?> p_265038_, java.util.function.Consumer<Packet<?>> out,
                                              ChannelHandlerContext ctx) {
        java.util.List<Packet<?>> collected = new java.util.ArrayList<>();
        instance.unbundlePacket(p_265038_, collected::add, ctx);
        if (collected.isEmpty()) {
            // NeoForge filtered out everything; don't produce an empty result
            // (which would crash the encoder). Just forward the original packet.
            out.accept(p_265038_);
        } else {
            collected.forEach(out);
        }
    }
}
