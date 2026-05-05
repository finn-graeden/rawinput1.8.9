package com.rawinput;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads raw mouse deltas from /dev/input/event* on Linux using the kernel
 * evdev interface. This bypasses the entire libinput/X11/Wayland stack,
 * giving true hardware counts identical to what Minecraft 1.21 delivers
 * with Raw Input enabled. RAW_SCALE is therefore always 1.0.
 *
 * Permissions: on modern systemd-logind systems the active graphical session
 * user automatically receives ACL access to input devices — no 'input' group
 * or sudo required on most distros including Arch/Garuda.
 *
 * Device discovery: scans /sys/class/input/event* for devices that report
 * both REL_X and REL_Y relative axes (i.e. mice). Reads all matching devices
 * in parallel so USB + built-in mice both work.
 *
 * struct input_event layout (64-bit Linux, little-endian x86_64):
 *   offset  0: __kernel_time_t  tv_sec   (8 bytes)
 *   offset  8: __kernel_suseconds_t tv_usec (8 bytes)
 *   offset 16: __u16 type               (2 bytes)
 *   offset 18: __u16 code               (2 bytes)
 *   offset 20: __s32 value              (4 bytes)
 *   total: 24 bytes
 *
 * Y-axis convention:
 *   evdev REL_Y positive = mouse moved DOWN (screen/kernel coords)
 *   We negate before storing so consumers see positive = UP,
 *   matching LWJGL's Mouse.getDY() convention.
 */
public class EvdevRawInput {

    private static final int EV_REL      = 2;
    private static final int REL_X       = 0;
    private static final int REL_Y       = 1;
    private static final int EVENT_SIZE  = 24; // 64-bit Linux

    private int accumDX = 0;
    private int accumDY = 0;
    private final Object lock = new Object();

    private volatile boolean running = false;
    private final List<Thread> readers = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    public boolean start() {
        List<String> devices = findMouseDevices();
        if (devices.isEmpty()) {
            RawInputMod.log.error("[RawInput] No mouse devices found under /sys/class/input.");
            return false;
        }

        List<String> opened = new ArrayList<>();
        for (String path : devices) {
            if (new File(path).canRead()) {
                opened.add(path);
            } else {
                RawInputMod.log.warn("[RawInput] Cannot read " + path
                        + " — skipping. If all devices fail, run: sudo usermod -aG input $USER");
            }
        }

        if (opened.isEmpty()) {
            RawInputMod.log.error("[RawInput] No readable mouse devices found.");
            RawInputMod.log.error("[RawInput] Add yourself to the input group: sudo usermod -aG input $USER");
            return false;
        }

        running = true;
        for (String path : opened) {
            final String p = path;
            Thread t = new Thread(() -> readLoop(p), "RawInput-evdev-" + path);
            t.setDaemon(true);
            t.start();
            readers.add(t);
            RawInputMod.log.info("[RawInput] Opened " + path);
        }
        return true;
    }

    public void stop() {
        running = false;
        readers.forEach(Thread::interrupt);
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Returns accumulated dx/dy since last call and resets them.
     * Always call every render tick (even when unfocused) to prevent
     * a camera jump on re-focus.
     * dy convention: positive = mouse moved UP (matches LWJGL getDY())
     */
    public int[] consumeDeltas() {
        synchronized (lock) {
            int[] r = { accumDX, accumDY };
            accumDX = 0;
            accumDY = 0;
            return r;
        }
    }

    // -----------------------------------------------------------------------
    // Device discovery
    // -----------------------------------------------------------------------

    /**
     * Scans /sys/class/input/event* for devices that have both REL_X (bit 0)
     * and REL_Y (bit 1) relative axes — the minimal definition of a mouse.
     */
    private List<String> findMouseDevices() {
        List<String> result = new ArrayList<>();
        File sysInput = new File("/sys/class/input");
        if (!sysInput.exists()) return result;

        File[] entries = sysInput.listFiles();
        if (entries == null) return result;

        for (File entry : entries) {
            if (!entry.getName().startsWith("event")) continue;

            File relCap = new File(entry, "device/capabilities/rel");
            if (!relCap.exists()) continue;

            try {
                String cap = readSmallFile(relCap).trim();
                if (cap.isEmpty()) continue;
                long bits = Long.parseLong(cap, 16);
                // Bit 0 = REL_X, bit 1 = REL_Y — both must be present.
                if ((bits & 0x3L) == 0x3L) {
                    result.add("/dev/input/" + entry.getName());
                }
            } catch (Exception ignored) {
                // Unparseable capability — not a standard mouse, skip.
            }
        }
        return result;
    }

    private String readSmallFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[64];
            int n = fis.read(buf);
            return n > 0 ? new String(buf, 0, n) : "";
        }
    }

    // -----------------------------------------------------------------------
    // Event loop
    // -----------------------------------------------------------------------

    private void readLoop(String devicePath) {
        try (FileInputStream fis = new FileInputStream(devicePath)) {
            byte[] buf = new byte[EVENT_SIZE];

            while (running) {
                // Read exactly one input_event (24 bytes).
                int read = 0;
                while (read < EVENT_SIZE) {
                    int n = fis.read(buf, read, EVENT_SIZE - read);
                    if (n < 0) {
                        RawInputMod.log.warn("[RawInput] EOF on " + devicePath);
                        return;
                    }
                    read += n;
                }

                // Parse type and code (little-endian u16 at offsets 16 and 18).
                int type  = (buf[16] & 0xFF) | ((buf[17] & 0xFF) << 8);
                int code  = (buf[18] & 0xFF) | ((buf[19] & 0xFF) << 8);
                // Parse value (little-endian s32 at offset 20).
                int value = (buf[20] & 0xFF)
                          | ((buf[21] & 0xFF) << 8)
                          | ((buf[22] & 0xFF) << 16)
                          | (buf[23]          << 24); // sign-extends high byte

                if (type == EV_REL) {
                    if      (code == REL_X) { synchronized (lock) { accumDX += value;  } }
                    // Negate REL_Y: evdev positive=DOWN, we store positive=UP.
                    else if (code == REL_Y) { synchronized (lock) { accumDY -= value;  } }
                }
            }

        } catch (IOException e) {
            if (running) {
                RawInputMod.log.error("[RawInput] IO error on " + devicePath + ": " + e.getMessage());
                running = false;
            }
        }
    }
}
