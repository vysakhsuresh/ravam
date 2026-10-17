package com.layerbit.ravam.capture.shell;

import android.os.ParcelFileDescriptor;

import java.io.OutputStream;

/**
 * The Shizuku user service. Shizuku starts this class in a process running as the shell
 * UID, and the app binds to it over the AIDL [IVoipCaptureService]. It owns a
 * [VoipCaptureCore] and pumps the captured far-end PCM into a pipe the app reads.
 *
 * Shizuku instantiates the no-arg constructor (or the Context one). We build our own
 * shell-attributed context inside the core, so either is fine.
 */
public final class VoipCaptureUserService extends IVoipCaptureService.Stub {

    private final VoipCaptureCore core = new VoipCaptureCore();
    private volatile boolean running = false;
    private Thread pump;

    public VoipCaptureUserService() {}
    public VoipCaptureUserService(android.content.Context context) {}

    @Override
    public ParcelFileDescriptor start(int sampleRate) {
        if (running) stop();
        if (!core.start(sampleRate)) {
            return null;
        }
        final ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (Throwable t) {
            core.stop();
            return null;
        }
        final ParcelFileDescriptor readEnd = pipe[0];
        final ParcelFileDescriptor writeEnd = pipe[1];
        running = true;

        pump = new Thread(() -> {
            OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
            byte[] buf = new byte[8192];
            try {
                while (running) {
                    int n = core.read(buf);
                    if (n < 0) break;
                    if (n == 0) { try { Thread.sleep(10); } catch (InterruptedException ie) { break; } continue; }
                    out.write(buf, 0, n);
                }
            } catch (Throwable ignored) {
                // App closed the read end, or capture ended. Fall through to cleanup.
            } finally {
                try { out.close(); } catch (Throwable ignored) {}
                core.stop();
                running = false;
            }
        }, "voip-pcm-pump");
        pump.setDaemon(true);
        pump.start();

        return readEnd;
    }

    @Override
    public String lastError() {
        return core.lastError();
    }

    @Override
    public void stop() {
        running = false;
        Thread p = pump;
        if (p != null) { p.interrupt(); }
        core.stop();
    }

    @Override
    public void destroy() {
        stop();
        System.exit(0);
    }
}
