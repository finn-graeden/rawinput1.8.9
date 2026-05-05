package com.rawinput;

import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;

/**
 * Replaces Minecraft's MouseHelper by overriding mouseXYChange().
 * Sets deltaX/deltaY directly from evdev hardware counts instead of
 * LWJGL's OS-processed values. This is the same approach used by
 * chromaticforge/RawInput — hooking at the source so all downstream
 * code (camera rotation, hand rendering) sees raw values from the start,
 * eliminating the need to correct prevRotation fields after the fact.
 */
public class RawMouseHelper extends MouseHelper {

    private final EvdevRawInput rawInput;

    public RawMouseHelper(EvdevRawInput rawInput) {
        this.rawInput = rawInput;
    }

    @Override
    public void grabMouseCursor() {
        // Clear stale deltas when game regains focus — prevents camera jump.
        rawInput.consumeDeltas();
        super.grabMouseCursor();
    }

    @Override
    public void mouseXYChange() {
        if (rawInput.isRunning()) {
            // Drain LWJGL's buffer so it never builds up stale values.
            Mouse.getDX();
            Mouse.getDY();

            int[] deltas = rawInput.consumeDeltas();
            deltaX = deltas[0];
            deltaY = deltas[1];
        } else {
            // Fallback to vanilla if evdev failed to open.
            super.mouseXYChange();
        }
    }
}
