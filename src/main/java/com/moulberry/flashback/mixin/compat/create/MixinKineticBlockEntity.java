package com.moulberry.flashback.mixin.compat.create;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes every Create kinetic block (water wheels, windmills, steam engines, cogwheels, shafts,
 * mechanical presses/mixers/drills, ...) keep rotating during a Flashback replay.
 *
 * <p>Create decides the visual rotation speed in {@code KineticBlockEntity#getSpeed()}:</p>
 *
 * <pre>{@code
 * public float getSpeed() {
 *     if (overStressed || (level != null && level.tickRateManager().isFrozen()))
 *         return 0;
 *     return getTheoreticalSpeed();
 * }
 * }</pre>
 *
 * <p>Both render paths consume this value - the classic BER
 * ({@code KineticBlockEntityRenderer#getAngleForBe} -> {@code be.getSpeed()}) and the Flywheel
 * instancing path ({@code RotatingInstance#setup} -> {@code blockEntity.getSpeed()}). Because
 * Flashback keeps the level's {@link net.minecraft.world.TickRateManager} frozen while a replay is
 * played back/scrubbed, {@code isFrozen()} is permanently {@code true}, so {@code getSpeed()}
 * collapses to {@code 0} and <em>nothing</em> in Create ever spins.
 *
 * <p>The underlying {@code speed} field is fine: it is written by
 * {@code KineticBlockEntity#write} ("Speed" NBT) and therefore already arrives at the replay client
 * through the recorded chunk / block-entity data. Only the frozen-check has to be bypassed.</p>
 *
 * <p>So while in a replay we return {@code getTheoreticalSpeed()} directly, ignoring the frozen
 * state (but still honouring {@code overStressed}, which is genuine recorded game state).</p>
 */
@IfModLoaded("create")
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.KineticBlockEntity", remap = false)
public abstract class MixinKineticBlockEntity {

    @Shadow
    protected boolean overStressed;

    @Shadow
    public abstract float getTheoreticalSpeed();

    @Inject(method = "getSpeed", at = @At("HEAD"), require = 0, cancellable = true)
    private void flashback$ignoreFrozenTickRate(CallbackInfoReturnable<Float> cir) {
        if (!Flashback.isInReplay()) {
            return;
        }

        if (this.overStressed) {
            cir.setReturnValue(0.0f);
        } else {
            cir.setReturnValue(this.getTheoreticalSpeed());
        }
    }
}
