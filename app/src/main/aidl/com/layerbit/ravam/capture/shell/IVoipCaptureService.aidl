package com.layerbit.ravam.capture.shell;

/**
 * Runs inside the Shizuku-spawned process at the shell UID (uid 2000), which holds
 * CAPTURE_VOICE_COMMUNICATION_OUTPUT and MODIFY_AUDIO_ROUTING. The app binds to this and
 * reads the far-end PCM from the returned pipe.
 */
interface IVoipCaptureService {
    /**
     * Register the privileged voice-communication capture policy and start recording.
     * Returns the read end of a pipe that streams raw 16-bit little-endian PCM mono at
     * [sampleRate], or null if the policy could not be registered. Call [lastError] then.
     */
    ParcelFileDescriptor start(int sampleRate);

    /** Human-readable reason the last start() returned null. */
    String lastError();

    /** Stop recording, unregister the policy, close the pipe. */
    void stop();

    /** Tear the service process down. */
    void destroy();
}
