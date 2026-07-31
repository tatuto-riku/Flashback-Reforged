package com.moulberry.flashback.mixin.playback;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor used to forcibly break vehicle/passenger links without invoking
 * {@code Entity#stopRiding} / {@code Entity#removePassenger}. Some modded vehicles
 * (e.g. Create's contraption entities, whose {@code contraption} field is only
 * populated on the real client from spawn data) crash when those callbacks run on
 * the replay server.
 */
@Mixin(Entity.class)
public interface EntityRideAccessor {

    @Accessor("vehicle")
    void flashback$setVehicle(Entity vehicle);

    @Accessor("passengers")
    void flashback$setPassengers(ImmutableList<Entity> passengers);

}
