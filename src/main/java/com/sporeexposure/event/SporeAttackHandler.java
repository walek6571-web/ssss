package com.sporeexposure.event;

import com.sporeexposure.SporeExposureMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Handles the progressive infection system when Spore mod entities attack players.
 *
 * Infection chain (in order):
 *   Stage 0 → Uneasy        (effect.spore.uneasy)         10 min = 12000 ticks
 *   Stage 1 → Biled         (effect.spore.biled)           8 min =  9600 ticks
 *   Stage 2 → Madness       (effect.spore.madness)         6 min =  7200 ticks
 *   Stage 3 → Symbiosis     (effect.spore.symbiosis)       4 min =  4800 ticks
 *   Stage 4 → Mycelium Inf. (effect.spore.mycelium_ef)     ∞     = Integer.MAX_VALUE
 *
 * A player wearing "item.lrarmor.chemical_protective_helmet" is fully immune.
 *
 * The next stage is applied when the current active stage effect expires
 * AND a Spore entity hits the player again, OR (for the permanent last stage) it
 * persists regardless.
 *
 * Implementation note:
 *   - We track the "highest stage reached" via a persistent NBT tag on the player.
 *   - On each hit from a Spore entity we check which stage the player is at and
 *     either apply the first stage (if none active) or advance to the next one
 *     if the previous has already expired.
 *   - Once Mycelium Infection is applied it is permanent (MAX_VALUE duration).
 */
public class SporeAttackHandler {

    // ── Spore effect resource locations ─────────────────────────────────────
    private static final ResourceLocation RL_UNEASY     = new ResourceLocation("spore", "uneasy");
    private static final ResourceLocation RL_BILED      = new ResourceLocation("spore", "biled");
    private static final ResourceLocation RL_MADNESS    = new ResourceLocation("spore", "madness");
    private static final ResourceLocation RL_SYMBIOSIS  = new ResourceLocation("spore", "symbiosis");
    private static final ResourceLocation RL_MYCELIUM   = new ResourceLocation("spore", "mycelium_ef");

    // ── Protective helmet item id ────────────────────────────────────────────
    private static final ResourceLocation RL_HELMET = new ResourceLocation("lrarmor", "chemical_protective_helmet");

    // ── Durations in ticks (20 ticks = 1 second) ────────────────────────────
    private static final int DURATION_UNEASY    = 20 * 60 * 10;   // 10 minutes
    private static final int DURATION_BILED     = 20 * 60 * 8;    //  8 minutes
    private static final int DURATION_MADNESS   = 20 * 60 * 6;    //  6 minutes
    private static final int DURATION_SYMBIOSIS = 20 * 60 * 4;    //  4 minutes
    private static final int DURATION_MYCELIUM  = Integer.MAX_VALUE; // permanent

    // Ordered chain of (effect RL, duration)
    private static final ResourceLocation[] CHAIN_EFFECTS = {
        RL_UNEASY, RL_BILED, RL_MADNESS, RL_SYMBIOSIS, RL_MYCELIUM
    };
    private static final int[] CHAIN_DURATIONS = {
        DURATION_UNEASY, DURATION_BILED, DURATION_MADNESS, DURATION_SYMBIOSIS, DURATION_MYCELIUM
    };

    // NBT key we use to store the highest stage ever applied
    private static final String NBT_STAGE_KEY = "SporeExposureStage";

    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        // Only care about players being hurt
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        // Check the attacker is from the Spore mod
        LivingEntity attacker = getSporeAttacker(event);
        if (attacker == null) return;

        // If the player is wearing the protective helmet → full immunity
        if (isWearingProtectiveHelmet(player)) return;

        // Determine and apply the appropriate infection stage
        applyProgressiveInfection(player);
    }

    /**
     * Returns the living entity source if it belongs to the "spore" mod namespace,
     * otherwise null.
     */
    private LivingEntity getSporeAttacker(LivingAttackEvent event) {
        // The damage source entity (direct attacker or indirect)
        net.minecraft.world.damagesource.DamageSource source = event.getSource();

        // Check direct entity
        if (source.getDirectEntity() instanceof LivingEntity le) {
            if (isSporeEntity(le)) return le;
        }
        // Check indirect (e.g. projectile owner)
        if (source.getEntity() instanceof LivingEntity le) {
            if (isSporeEntity(le)) return le;
        }
        return null;
    }

    /**
     * Returns true if the entity's type is registered under the "spore" namespace.
     */
    private boolean isSporeEntity(LivingEntity entity) {
        ResourceLocation typeRL = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return typeRL != null && "spore".equals(typeRL.getNamespace());
    }

    /**
     * Checks whether the player has the Chemical Protective Helmet in their head slot.
     */
    private boolean isWearingProtectiveHelmet(Player player) {
        ItemStack helmet = player.getInventory().armor.get(3); // index 3 = head
        if (helmet.isEmpty()) return false;
        ResourceLocation itemRL = ForgeRegistries.ITEMS.getKey(helmet.getItem());
        return RL_HELMET.equals(itemRL);
    }

    /**
     * Core logic: figure out which stage the player is at and apply
     * the correct next (or current first) effect.
     *
     * Rules:
     * - Read the highest stage the player has ever reached (NBT tag).
     * - If the player currently HAS an active effect from that stage, do nothing
     *   (the effect is still running; wait for it to expire naturally).
     * - If the player does NOT have that effect active any more, advance to the
     *   next stage (or re-apply the final permanent stage if already at max).
     * - On first hit (no NBT tag), apply stage 0 (Uneasy).
     */
    private void applyProgressiveInfection(ServerPlayer player) {
        int currentStage = player.getPersistentData().getInt(NBT_STAGE_KEY); // defaults to 0

        boolean hasNbtStage = player.getPersistentData().contains(NBT_STAGE_KEY);

        if (!hasNbtStage) {
            // Very first hit ever — apply stage 0
            applyStage(player, 0);
            return;
        }

        // Player has been hit before. Check if the current stage effect is still active.
        ResourceLocation currentEffectRL = CHAIN_EFFECTS[currentStage];
        MobEffect currentEffect = ForgeRegistries.MOB_EFFECTS.getValue(currentEffectRL);

        if (currentEffect != null && player.hasEffect(currentEffect)) {
            // Effect still running — do nothing, they're already infected at this stage
            return;
        }

        // Effect has expired (or wasn't applied somehow). Advance to next stage.
        int nextStage = Math.min(currentStage + 1, CHAIN_EFFECTS.length - 1);
        applyStage(player, nextStage);
    }

    /**
     * Applies the effect for the given stage index and saves the stage to NBT.
     */
    private void applyStage(ServerPlayer player, int stageIndex) {
        ResourceLocation effectRL = CHAIN_EFFECTS[stageIndex];
        int duration = CHAIN_DURATIONS[stageIndex];

        MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(effectRL);
        if (effect == null) {
            SporeExposureMod.LOGGER.warn(
                "[SporeExposure] Could not find effect '{}' from the Spore mod. " +
                "Make sure the Spore mod is installed!", effectRL
            );
            return;
        }

        // Remove any lingering earlier-stage effects to keep things clean
        removeAllChainEffects(player);

        // Apply the new stage effect (amplifier 0 = level I, showParticles = true)
        player.addEffect(new MobEffectInstance(effect, duration, 0, false, true, true));

        // Persist the stage
        player.getPersistentData().putInt(NBT_STAGE_KEY, stageIndex);

        SporeExposureMod.LOGGER.debug(
            "[SporeExposure] Applied stage {} ({}) to player {}",
            stageIndex, effectRL, player.getName().getString()
        );
    }

    /**
     * Removes all chain effects from the player (used before applying the next stage).
     */
    private void removeAllChainEffects(ServerPlayer player) {
        for (ResourceLocation rl : CHAIN_EFFECTS) {
            MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(rl);
            if (effect != null) {
                player.removeEffect(effect);
            }
        }
    }
}
