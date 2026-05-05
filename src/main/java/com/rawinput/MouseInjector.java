package com.rawinput;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Field;

/**
 * On the first client tick, replaces mc.mouseHelper with RawMouseHelper.
 * After that, mouseXYChange() handles everything — no rotation correction
 * needed, no prevRotation hacks, no hand jitter.
 */
public class MouseInjector {

    private final EvdevRawInput rawInput;
    private boolean replaced = false;

    public MouseInjector(EvdevRawInput rawInput) {
        this.rawInput = rawInput;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (replaced || event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;

        try {
            // Find the mouseHelper field by scanning for a field whose type
            // is assignable from the current mouseHelper instance — avoids
            // needing to know the SRG obfuscated field name.
            Field mouseHelperField = null;
            for (Field f : mc.getClass().getDeclaredFields()) {
                if (mc.mouseHelper != null &&
                        f.getType().isAssignableFrom(mc.mouseHelper.getClass())) {
                    f.setAccessible(true);
                    mouseHelperField = f;
                    break;
                }
            }

            if (mouseHelperField == null) {
                RawInputMod.log.error("[RawInput] Could not find mouseHelper field on Minecraft class.");
                replaced = true;
                return;
            }

            mouseHelperField.set(mc, new RawMouseHelper(rawInput));
            replaced = true;
            RawInputMod.log.info("[RawInput] MouseHelper replaced — raw input active.");

        } catch (Exception e) {
            RawInputMod.log.error("[RawInput] Failed to replace MouseHelper: " + e);
            replaced = true;
        }
    }
}