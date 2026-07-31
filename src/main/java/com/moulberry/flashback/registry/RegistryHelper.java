package com.moulberry.flashback.registry;

import com.moulberry.flashback.Flashback;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.multiplayer.ClientRegistryLayer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RegistryHelper {

    // Checks if all the given registries in one match two
    public static boolean equals(RegistryAccess one, RegistryAccess two, List<RegistryDataLoader.RegistryData<?>> registries) {
        List<Registry<?>> listOne = new ArrayList<>();
        List<Registry<?>> listTwo = new ArrayList<>();

        // Check counts first, as a fast path
        for (RegistryDataLoader.RegistryData<?> registryData : registries) {
            Optional<? extends Registry<?>> registryOne = one.registry(registryData.key());
            Optional<? extends Registry<?>> registryTwo = two.registry(registryData.key());

            if (registryOne.isEmpty() || registryTwo.isEmpty()) {
                if (registryOne.isEmpty() && registryTwo.isEmpty()) {
                    continue;
                } else {
                    return false;
                }
            }

            if (registryOne.get().size() != registryTwo.get().size()) {
                return false;
            }

            listOne.add(registryOne.get());
            listTwo.add(registryTwo.get());
        }

        class FrozenAccess extends RegistryAccess.ImmutableRegistryAccess implements RegistryAccess.Frozen {
            public FrozenAccess(List<? extends Registry<?>> list) {
                super(list);
            }
        }

        LayeredRegistryAccess<?> layeredOne = ClientRegistryLayer.createRegistryAccess().replaceFrom(ClientRegistryLayer.REMOTE, new FrozenAccess(listOne));
        LayeredRegistryAccess<?> layeredTwo = ClientRegistryLayer.createRegistryAccess().replaceFrom(ClientRegistryLayer.REMOTE, new FrozenAccess(listTwo));

        RegistryOps<?> dynamicOpsOne = RegistryOps.create(JsonOps.INSTANCE, layeredOne.compositeAccess());
        RegistryOps<?> dynamicOpsTwo = RegistryOps.create(JsonOps.INSTANCE, layeredTwo.compositeAccess());

        // Check if all objects match
        for (RegistryDataLoader.RegistryData<?> registryData : registries) {
            if (!equalsAssumeSameSize(layeredOne.compositeAccess(), layeredTwo.compositeAccess(), dynamicOpsOne, dynamicOpsTwo, registryData)) {
                return false;
            }
        }

        return true;
    }

    private static <T> boolean equalsAssumeSameSize(RegistryAccess one, RegistryAccess two, RegistryOps<?> dynamicOpsOne, RegistryOps<?> dynamicOpsTwo,
        RegistryDataLoader.RegistryData<T> registryData) {
        Optional<? extends Registry<T>> registryOne = one.registry(registryData.key());
        Optional<? extends Registry<T>> registryTwo = two.registry(registryData.key());

        if (registryOne.isEmpty() || registryTwo.isEmpty()) {
            return registryOne.isEmpty() && registryTwo.isEmpty();
        }

        Iterator<T> iteratorOne = registryOne.get().iterator();
        Iterator<T> iteratorTwo = registryTwo.get().iterator();

        while (iteratorOne.hasNext()) {
            T valueOne = iteratorOne.next();
            T valueTwo = iteratorTwo.next();

            if (!equalsUsingCodec(valueOne, valueTwo, dynamicOpsOne, dynamicOpsTwo, registryData.elementCodec())) {
                return false;
            }
        }

        return true;
    }

    private static <T> boolean equalsUsingCodec(T one, T two, RegistryOps<?> dynamicOpsOne, RegistryOps<?> dynamicOpsTwo, Codec<T> codec) {
        if (one.equals(two)) {
            return true;
        }

        var resultOne = codec.encodeStart(dynamicOpsOne, one);
        if (resultOne.isError()) {
            return false;
        }

        var resultTwo = codec.encodeStart(dynamicOpsTwo, two);
        if (resultTwo.isError()) {
            return false;
        }

        return resultOne.getOrThrow().equals(resultTwo.getOrThrow());
    }

    // ------------------------------------------------------------------
    // Value-identity preservation
    //
    // MappedRegistry looks values up by *reference* (toId is a Reference2IntMap,
    // byValue is an IdentityHashMap). When Flashback swaps the replay server's
    // synchronized registries for freshly loaded ones, every object that other
    // mods cached before the swap (e.g. a Holder<BannerPattern> baked into an
    // ItemStack data component) becomes unknown to the new registry, even though
    // an entry with the very same key/content exists. Encoding such an object
    // then fails with "Can't find id for '<entry>'" and kicks the viewer.
    //
    // To avoid that we alias every value instance of the previous registry into
    // the new registry's identity maps, pointing at the entry with the same key.
    // ------------------------------------------------------------------

    private static final java.lang.reflect.Field MAPPED_REGISTRY_TO_ID = findField("toId");
    private static final java.lang.reflect.Field MAPPED_REGISTRY_BY_VALUE = findField("byValue");

    private static java.lang.reflect.Field findField(String name) {
        try {
            java.lang.reflect.Field field = MappedRegistry.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            Flashback.LOGGER.warn("[Flashback] RegistryHelper: unable to access MappedRegistry." + name, e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> getMap(java.lang.reflect.Field field, Object instance) {
        if (field == null) {
            return null;
        }
        try {
            return (Map<Object, Object>) field.get(instance);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void preserveValueIdentity(Registry<?> oldRegistry, Registry<?> newRegistry) {
        if (oldRegistry == null || newRegistry == null || oldRegistry == newRegistry) {
            return;
        }
        if (!(newRegistry instanceof MappedRegistry<?> newMapped) || !(oldRegistry instanceof MappedRegistry<?> oldMapped)) {
            return;
        }

        Map<Object, Object> newToId = getMap(MAPPED_REGISTRY_TO_ID, newMapped);
        Map<Object, Object> newByValue = getMap(MAPPED_REGISTRY_BY_VALUE, newMapped);
        Map<Object, Object> oldByValue = getMap(MAPPED_REGISTRY_BY_VALUE, oldMapped);
        if (newToId == null || newByValue == null || oldByValue == null) {
            return;
        }

        Registry rawNew = newRegistry;
        int aliased = 0;

        // Iterating the raw byValue map (instead of entrySet) also carries over aliases
        // that were installed by a previous registry swap.
        for (Map.Entry<Object, Object> entry : new ArrayList<>(oldByValue.entrySet())) {
            Object oldValue = entry.getKey();
            if (oldValue == null || newByValue.containsKey(oldValue)) {
                continue;
            }
            if (!(entry.getValue() instanceof Holder.Reference<?> oldHolder)) {
                continue;
            }

            ResourceKey<?> key = oldHolder.key();
            Holder.Reference newHolder = (Holder.Reference) rawNew.getHolder((ResourceKey) key).orElse(null);
            if (newHolder == null) {
                continue;
            }

            int id = rawNew.getId(newHolder.value());
            if (id < 0) {
                continue;
            }

            newByValue.put(oldValue, newHolder);
            newToId.put(oldValue, id);
            aliased++;
        }

        if (aliased > 0) {
            Flashback.LOGGER.debug("[Flashback] preserveValueIdentity: aliased " + aliased + " stale value instance(s) into " +
                newRegistry.key().location());
        }
    }

}
