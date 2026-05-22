package com.sporeexposure;

import com.mojang.logging.LogUtils;
import com.sporeexposure.event.SporeAttackHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(SporeExposureMod.MODID)
public class SporeExposureMod {

    public static final String MODID = "sporeexposure";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SporeExposureMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register the event handler on the Forge bus (world/entity events)
        MinecraftForge.EVENT_BUS.register(new SporeAttackHandler());

        LOGGER.info("Spore Exposure Mod initialized.");
    }
}
