package com.rawinput;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.apache.logging.log4j.Logger;

@Mod(
    modid          = RawInputMod.MODID,
    name           = RawInputMod.NAME,
    version        = RawInputMod.VERSION,
    clientSideOnly = true
)
public class RawInputMod {

    public static final String MODID   = "rawinputmod";
    public static final String NAME    = "Raw Input Mod";
    public static final String VERSION = "1.2.0";

    public static Logger log;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        log = event.getModLog();
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            log.info("[RawInput] Not on Linux — mod inactive.");
            return;
        }

        EvdevRawInput rawInput = new EvdevRawInput();
        if (rawInput.start()) {
            MinecraftForge.EVENT_BUS.register(new MouseInjector(rawInput));
        } else {
            log.error("[RawInput] Could not start — see above for details.");
        }
    }
}
