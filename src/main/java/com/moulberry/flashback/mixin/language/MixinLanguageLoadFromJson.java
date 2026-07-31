package com.moulberry.flashback.mixin.language;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.locale.Language;
import net.minecraft.util.GsonHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.BiConsumer;

@Mixin(Language.class)
public class MixinLanguageLoadFromJson {

    @Unique
    private static final Gson FLASHBACK_GSON = new Gson();

    @Inject(method = "loadFromJson", at = @At("HEAD"), cancellable = true)
    private static void loadFromJson(InputStream inputStream, BiConsumer<String, String> biConsumer, CallbackInfo ci) {
        JsonObject jsonObject = FLASHBACK_GSON.fromJson(new InputStreamReader(inputStream, StandardCharsets.UTF_8), JsonObject.class);

        for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            if (entry.getValue().isJsonNull()) {
                continue;
            }
            biConsumer.accept(entry.getKey(), GsonHelper.convertToString(entry.getValue(), entry.getKey()));
        }

        ci.cancel();
    }

}
