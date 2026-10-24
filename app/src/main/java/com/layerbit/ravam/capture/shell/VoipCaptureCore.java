package com.layerbit.ravam.capture.shell;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.os.Looper;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The privileged VoIP capture mechanism — spike E2, proven on hardware and now shipping.
 *
 * Runs inside the Shizuku-spawned process at the shell UID (2000), which holds
 * CAPTURE_VOICE_COMMUNICATION_OUTPUT and MODIFY_AUDIO_ROUTING. It registers a dynamic
 * AudioPolicy whose mixing rule sets voiceCommunicationCaptureAllowed(true) and
 * allowPrivilegedPlaybackCapture(true), matching USAGE_VOICE_COMMUNICATION players — the
 * far end of any VoIP app (BOTIM, WhatsApp, Telegram, Signal…), by usage, not package.
 *
 * Three findings from the spike are baked in and must not be undone casually:
 *  1. The privileged mix caps sample rate at 16 kHz; 48 kHz is rejected by the framework.
 *  2. Capture from a shell process is denied at the AppOps layer unless the recorder is
 *     attributed to com.android.shell (a package uid 2000 owns). Hence [FakeShellContext]
 *     and a self-built REMOTE_SUBMIX sink instead of AudioPolicy.createAudioRecordSink,
 *     whose internal AudioRecord attributes to "android" and is refused.
 *  3. Route flag LOOP_BACK alone *diverts* the far end away from the earpiece, so the
 *     user hears nothing. LOOP_BACK | RENDER (0x3) captures and still plays it out.
 *
 * All audio-policy classes are hidden/@SystemApi, so everything here is reflection. The
 * Shizuku process is app_process spawned by the shell-uid server, exempt from the
 * hidden-API blacklist, so the reflection is permitted.
 */
public final class VoipCaptureCore {

    private static final int REMOTE_SUBMIX = 8; // MediaRecorder.AudioSource.REMOTE_SUBMIX
    private static final int ROUTE_LOOPBACK_RENDER = 0x3; // capture AND keep playing to the ear

    private AudioManager audioManager;
    private Object policy;
    private AudioRecord record;
    private int rate = 16000;
    private int channels = 1;
    private String lastError;

    public String lastError() { return lastError; }
    public int sampleRate() { return rate; }

    /** Register the policy and start the recorder. Returns true on success. */
    public boolean start(int sampleRate) {
        try {
            this.rate = sampleRate;
            Context sys = systemContext();
            fillAppInfo("com.android.shell");
            AttributionSource attr = shellAttribution(Process.myUid(), "com.android.shell");
            Context ctx = new FakeShellContext(sys, attr);
            audioManager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

            Object rule = buildRule(AudioAttributes.USAGE_VOICE_COMMUNICATION);
            Object mix = buildMix(rule, sampleRate, AudioFormat.CHANNEL_OUT_MONO, ROUTE_LOOPBACK_RENDER);
            policy = buildPolicy(ctx, mix);

            int res = registerPolicy(audioManager, policy);
            if (res != 0) { lastError = "registerAudioPolicy returned " + res; policy = null; return false; }

            record = createRecordSink(mix, ctx, sampleRate, channels);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                lastError = "record sink did not initialise";
                cleanup();
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                lastError = "sink opened but would not start";
                cleanup();
                return false;
            }
            return true;
        } catch (Throwable t) {
            lastError = String.valueOf(rootCause(t));
            cleanup();
            return false;
        }
    }

    /**
     * DO NOT CALL ON A LIVE CALL — currently unused. On the Moto G84 (Qualcomm) opening this
     * while the call is on a Bluetooth headset crashes the audio DSP and the HAL ends the call
     * (seen 2026-10-24, two of three calls dropped). Kept only for future bench testing.
     *
     * Start capturing the far end of a *carrier* call, from the telephony downlink.
     *
     * Unlike {@link #start}, this opens no AudioPolicy and does no loop-back re-render: it is
     * a plain {@link AudioRecord} on VOICE_DOWNLINK (source 3), attributed to com.android.shell
     * — which on this device holds CAPTURE_AUDIO_OUTPUT. Because nothing is re-rendered into the
     * call, the far party hears no buzz, and because the downlink is tapped directly it works on
     * a Bluetooth headset as well as the earpiece. The near side is captured separately by the
     * app's own microphone, exactly as for the VoIP path, so the two still become a stereo file.
     *
     * If a device's audio HAL refuses to feed the downlink (some do), the capture simply
     * delivers silence — a safe failure, never a buzz.
     */
    public boolean startDownlink(int sampleRate) {
        final int VOICE_DOWNLINK = 3; // MediaRecorder.AudioSource.VOICE_DOWNLINK
        try {
            this.rate = sampleRate;
            Context sys = systemContext();
            fillAppInfo("com.android.shell");
            AttributionSource attr = shellAttribution(Process.myUid(), "com.android.shell");
            Context ctx = new FakeShellContext(sys, attr);

            AudioFormat inFmt = new AudioFormat.Builder().setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            int buf = Math.max(
                    AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT), 4096) * 4;

            AudioRecord.Builder rb = new AudioRecord.Builder()
                    .setAudioSource(VOICE_DOWNLINK)
                    .setAudioFormat(inFmt)
                    .setBufferSizeInBytes(buf);
            // setContext is @SystemApi/hidden; the shell-attributed context is what lets the
            // AppOps check pass (CAPTURE_AUDIO_OUTPUT is granted to com.android.shell).
            AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(rb, ctx);
            record = rb.build();

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                lastError = "downlink sink did not initialise";
                cleanup();
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                lastError = "downlink opened but would not start";
                cleanup();
                return false;
            }
            return true;
        } catch (Throwable t) {
            lastError = String.valueOf(rootCause(t));
            cleanup();
            return false;
        }
    }

    /** Read PCM into [buf]; returns bytes read, 0 if none yet, negative on error. */
    public int read(byte[] buf) {
        AudioRecord r = record;
        if (r == null) return -1;
        return r.read(buf, 0, buf.length);
    }

    public void stop() { cleanup(); }

    private void cleanup() {
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) {}
            try { r.release(); } catch (Throwable ignored) {}
        }
        Object p = policy;
        policy = null;
        if (p != null && audioManager != null) {
            try { unregister(audioManager, p); } catch (Throwable ignored) {}
        }
    }

    // ---- reflection plumbing (see spike tool) ------------------------------

    private static Throwable rootCause(Throwable t) {
        return (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
    }

    private static Context systemContext() throws Exception {
        if (Looper.myLooper() == null) Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        return (Context) at.getMethod("getSystemContext").invoke(thread);
    }

    private static void fillAppInfo(String pkg) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getDeclaredMethod("currentActivityThread").invoke(null);
            Field boundF = at.getDeclaredField("mBoundApplication");
            boundF.setAccessible(true);
            Object bound = boundF.get(thread);
            Class<?> bindDataC = Class.forName("android.app.ActivityThread$AppBindData");
            if (bound == null) {
                Constructor<?> c = bindDataC.getDeclaredConstructor();
                c.setAccessible(true);
                bound = c.newInstance();
                boundF.set(thread, bound);
            }
            Field appInfoF = bindDataC.getDeclaredField("appInfo");
            appInfoF.setAccessible(true);
            ApplicationInfo info = (ApplicationInfo) appInfoF.get(bound);
            if (info == null) { info = new ApplicationInfo(); appInfoF.set(bound, info); }
            info.packageName = pkg;
            try {
                Class<?> compatC = Class.forName("android.content.res.CompatibilityInfo");
                Object defCompat = compatC.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
                Object loadedApk = at.getMethod("getPackageInfoNoCheck", ApplicationInfo.class, compatC)
                        .invoke(thread, info, defCompat);
                Field infoF = bindDataC.getDeclaredField("info");
                infoF.setAccessible(true);
                infoF.set(bound, loadedApk);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static AttributionSource shellAttribution(int uid, String pkg) throws Exception {
        Class<?> bC = Class.forName("android.content.AttributionSource$Builder");
        Object b = bC.getConstructor(int.class).newInstance(uid);
        bC.getMethod("setPackageName", String.class).invoke(b, pkg);
        return (AttributionSource) bC.getMethod("build").invoke(b);
    }

    static final class FakeShellContext extends ContextWrapper {
        private final AttributionSource attr;
        FakeShellContext(Context base, AttributionSource attr) { super(base); this.attr = attr; }
        @Override public String getPackageName() { return "com.android.shell"; }
        @Override public String getOpPackageName() { return "com.android.shell"; }
        @Override public AttributionSource getAttributionSource() { return attr; }
        @Override public Context getApplicationContext() { return this; }
    }

    private static int intField(Class<?> c, String name) throws Exception {
        return c.getField(name).getInt(null);
    }

    private static Object buildRule(int usage) throws Exception {
        Class<?> ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> builderC = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Object b = builderC.getConstructor().newInstance();
        try {
            int MIX_ROLE_PLAYERS = intField(ruleC, "MIX_ROLE_PLAYERS");
            builderC.getMethod("setTargetMixRole", int.class).invoke(b, MIX_ROLE_PLAYERS);
        } catch (Throwable ignored) {}
        AudioAttributes attr = new AudioAttributes.Builder().setUsage(usage).build();
        int RULE = intField(ruleC, "RULE_MATCH_ATTRIBUTE_USAGE");
        builderC.getMethod("addMixRule", int.class, Object.class).invoke(b, RULE, attr);
        tryBool(builderC, b, "allowPrivilegedPlaybackCapture", true);
        tryBool(builderC, b, "voiceCommunicationCaptureAllowed", true);
        return builderC.getMethod("build").invoke(b);
    }

    private static void tryBool(Class<?> c, Object target, String method, boolean v) {
        try { c.getMethod(method, boolean.class).invoke(target, v); } catch (Throwable ignored) {}
    }

    private static Object buildMix(Object rule, int rate, int outMask, int routeFlags) throws Exception {
        Class<?> ruleC = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Class<?> builderC = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Object b = builderC.getConstructor(ruleC).newInstance(rule);
        AudioFormat fmt = new AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(outMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
        builderC.getMethod("setFormat", AudioFormat.class).invoke(b, fmt);
        builderC.getMethod("setRouteFlags", int.class).invoke(b, routeFlags);
        return builderC.getMethod("build").invoke(b);
    }

    private static Object buildPolicy(Context ctx, Object mix) throws Exception {
        Class<?> mixC = Class.forName("android.media.audiopolicy.AudioMix");
        Class<?> builderC = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Object b = builderC.getConstructor(Context.class).newInstance(ctx);
        builderC.getMethod("addMix", mixC).invoke(b, mix);
        return builderC.getMethod("build").invoke(b);
    }

    private static int registerPolicy(AudioManager am, Object policy) throws Exception {
        Class<?> policyC = Class.forName("android.media.audiopolicy.AudioPolicy");
        return (Integer) AudioManager.class.getMethod("registerAudioPolicy", policyC).invoke(am, policy);
    }

    private static void unregister(AudioManager am, Object policy) throws Exception {
        Class<?> policyC = Class.forName("android.media.audiopolicy.AudioPolicy");
        try {
            AudioManager.class.getMethod("unregisterAudioPolicy", policyC).invoke(am, policy);
        } catch (NoSuchMethodException e) {
            AudioManager.class.getMethod("unregisterAudioPolicyAsync", policyC).invoke(am, policy);
        }
    }

    /** Build the capture AudioRecord ourselves so it carries the shell-attributed context. */
    private static AudioRecord createRecordSink(Object mix, Context ctx, int rate, int chans) throws Exception {
        Class<?> mixC = Class.forName("android.media.audiopolicy.AudioMix");
        String reg = (String) mixC.getMethod("getRegistration").invoke(mix);

        int inMask = chans == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        AudioFormat inFmt = new AudioFormat.Builder().setSampleRate(rate)
                .setChannelMask(inMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();

        AudioAttributes.Builder ab = new AudioAttributes.Builder();
        AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class).invoke(ab, REMOTE_SUBMIX);
        AudioAttributes.Builder.class.getMethod("addTag", String.class).invoke(ab, "addr=" + reg);
        AudioAttributes aa = ab.build();

        int buf = Math.max(AudioRecord.getMinBufferSize(rate, inMask, AudioFormat.ENCODING_PCM_16BIT), 4096) * 4;
        AudioRecord.Builder rb = new AudioRecord.Builder().setAudioFormat(inFmt).setBufferSizeInBytes(buf);
        AudioRecord.Builder.class.getMethod("setAudioAttributes", AudioAttributes.class).invoke(rb, aa);
        AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(rb, ctx);
        return rb.build();
    }
}
