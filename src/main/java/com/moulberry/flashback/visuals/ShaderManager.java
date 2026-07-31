package com.moulberry.flashback.visuals;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

public class ShaderManager {

    public static final ShaderManager INSTANCE = new ShaderManager();

    public static ShaderInstance blitScreenRoundAlpha;
    public static ShaderInstance blitScreenFlip;

    public void register() {
        // Registration handled via event bus subscriber
    }

    @EventBusSubscriber(modid = "flashback_reforged", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ShaderEvents {
        @SubscribeEvent
        public static void registerShaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.parse("flashback:blit_screen_round_alpha"),
                    DefaultVertexFormat.BLIT_SCREEN),
                shaderInstance -> blitScreenRoundAlpha = shaderInstance
            );
            event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.parse("flashback:blit_screen_flip"),
                    DefaultVertexFormat.BLIT_SCREEN),
                shaderInstance -> blitScreenFlip = shaderInstance
            );
        }
    }

}
