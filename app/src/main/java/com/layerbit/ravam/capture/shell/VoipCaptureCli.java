package com.layerbit.ravam.capture.shell;

import java.io.OutputStream;

/**
 * Command-line entry point for the privileged capture, run as the shell UID over the
 * embedded ADB connection (PLAN.md §2.3). Ravam pairs with the phone's own Wireless
 * Debugging and runs:
 *
 *   CLASSPATH=&lt;ravam.apk&gt; app_process / com.layerbit.ravam.capture.shell.VoipCaptureCli 16000
 *
 * app_process loads these (pure-Java, no Kotlin/AndroidX) classes from the APK as uid
 * 2000, which is exempt from the hidden-API blacklist, so [VoipCaptureCore]'s reflection
 * works. Raw 16-bit little-endian PCM is written to stdout; the app reads it off the ADB
 * shell stream. Diagnostics go to stderr so they never corrupt the audio.
 *
 * Capture stops when the app closes the stream: stdin hits EOF, or the stdout write breaks.
 */
public final class VoipCaptureCli {

    public static void main(String[] args) {
        int rate = args.length > 0 ? safeInt(args[0], 16000) : 16000;
        // Second arg "carrier" taps the telephony downlink (VOICE_DOWNLINK) for a cellular
        // call instead of the VoIP voice-communication mix. No loop-back, so no buzz.
        boolean carrier = args.length > 1 && "carrier".equals(args[1].trim());

        VoipCaptureCore core = new VoipCaptureCore();
        boolean started = carrier ? core.startDownlink(rate) : core.start(rate);
        if (!started) {
            System.err.println("E2_FAIL:" + core.lastError());
            System.exit(2);
            return;
        }
        System.err.println("E2_OK:" + rate + (carrier ? ":carrier" : ""));

        // Stop cleanly when the app closes the ADB stream (stdin reaches EOF).
        final Thread main = Thread.currentThread();
        Thread stdinWatch = new Thread(() -> {
            try {
                byte[] b = new byte[64];
                while (System.in.read(b) >= 0) { /* drain */ }
            } catch (Throwable ignored) {
            }
            core.stop();
            main.interrupt();
        }, "stdin-eof");
        stdinWatch.setDaemon(true);
        stdinWatch.start();

        OutputStream out = System.out;
        byte[] buf = new byte[8192];
        try {
            while (!Thread.currentThread().isInterrupted()) {
                int n = core.read(buf);
                if (n < 0) break;
                if (n == 0) { try { Thread.sleep(10); } catch (InterruptedException ie) { break; } continue; }
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable t) {
            System.err.println("E2_END:" + t);
        } finally {
            core.stop();
        }
        System.exit(0);
    }

    private static int safeInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }
}
