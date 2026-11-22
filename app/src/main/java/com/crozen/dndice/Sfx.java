package com.crozen.dndice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * All sounds are synthesized in code (no audio assets): a die clacking on a wooden table,
 * a magical shimmer when it is thrown, a resonant bell when it lands, and big swells for
 * critical success / failure, all run through a small hall reverb.
 * They are rendered once on a background thread, cached as WAV and played with SoundPool.
 */
final class Sfx {

    static final int SR = 44100;
    private static final String VERSION = "sfx3";

    private final SoundPool pool;
    private final Vibrator vib;
    private final Random rng = new Random();
    private final int[] clack = new int[4];
    private volatile int whoosh, land, success, failure, critWin, critFail, tick, select;
    boolean soundOn = true;

    Sfx(Context ctx) {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(10).setAudioAttributes(aa).build();
        vib = ctx.getSystemService(Vibrator.class);
        final File dir = new File(ctx.getCacheDir(), VERSION);
        Thread t = new Thread(() -> loadAll(dir), "sfx-synth");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        t.start();
    }

    private void loadAll(File dir) {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        tick = load(dir, "tick", () -> tickSound(false));
        select = load(dir, "select", () -> tickSound(true));
        for (int i = 0; i < clack.length; i++) {
            final int k = i;
            clack[i] = load(dir, "clack" + i, () -> clackSound(k));
        }
        whoosh = load(dir, "whoosh", Sfx::whooshSound);
        land = load(dir, "land", Sfx::landSound);
        success = load(dir, "success", Sfx::successSound);
        failure = load(dir, "failure", Sfx::failureSound);
        critWin = load(dir, "critwin", Sfx::critWinSound);
        critFail = load(dir, "critfail", Sfx::critFailSound);
    }

    // ------------------------------------------------------------------ public API

    void throwStart() {
        play(whoosh, 0.75f, 1f);
    }

    /** One impact of a die on the table; strength 0..1. */
    void impact(float strength) {
        int id = clack[rng.nextInt(clack.length)];
        play(id, 0.35f + 0.65f * strength, 0.9f + rng.nextFloat() * 0.22f);
        buzz(8 + (int) (14 * strength), (int) (60 + 160 * strength));
    }

    void landed() {
        play(land, 0.8f, 1f);
        buzz(22, 150);
    }

    void verdict(boolean ok) {
        if (ok) {
            play(success, 0.9f, 1f);
            pattern(new long[]{0, 30, 70, 50}, new int[]{0, 150, 0, 220});
        } else {
            play(failure, 0.9f, 1f);
            pattern(new long[]{0, 90}, new int[]{0, 170});
        }
    }

    void critSuccess() {
        play(critWin, 1f, 1f);
        pattern(new long[]{0, 40, 50, 40, 50, 60, 80, 220}, new int[]{0, 200, 0, 200, 0, 230, 0, 255});
    }

    void critFailure() {
        play(critFail, 1f, 1f);
        pattern(new long[]{0, 180, 90, 260}, new int[]{0, 255, 0, 200});
    }

    void tick() {
        play(tick, 0.45f, 1f);
        buzz(6, 90);
    }

    void select() {
        play(select, 0.6f, 1f);
        buzz(10, 110);
    }

    void release() {
        pool.release();
    }

    // ------------------------------------------------------------------ playback

    private void play(int id, float vol, float rate) {
        if (soundOn && id != 0) pool.play(id, vol, vol, 1, 0, rate);
    }

    private void buzz(long ms, int amp) {
        if (vib == null || !vib.hasVibrator()) return;
        vib.vibrate(VibrationEffect.createOneShot(ms, vib.hasAmplitudeControl() ? Math.min(255, amp) : VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void pattern(long[] timings, int[] amps) {
        if (vib == null || !vib.hasVibrator()) return;
        if (vib.hasAmplitudeControl()) vib.vibrate(VibrationEffect.createWaveform(timings, amps, -1));
        else vib.vibrate(VibrationEffect.createWaveform(timings, -1));
    }

    private interface Gen { short[] make(); }

    private int load(File dir, String name, Gen gen) {
        File f = new File(dir, name + ".wav");
        try {
            if (!f.exists() || f.length() < 100) writeWav(f, gen.make());
            return pool.load(f.getPath(), 1);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ sound design

    /** A die hitting a wooden table: knock of the table, click of the corner, ring of the resin. */
    static short[] clackSound(int variant) {
        Random r = new Random(100 + variant);
        float[] b = buf(0.16f);
        float body = 150 + variant * 22;
        partial(b, 0, 0.12f, body, 0.55f, 0.0008f, 0.028f);
        partial(b, 0, 0.12f, body * 2.3f, 0.25f, 0.0008f, 0.018f);
        float[] click = buf(0.05f);
        noise(click, 0, 0.04f, 1f, 0.0002f, 0.004f, r);
        bandpass(click, 2600 + variant * 450, 2.2f);
        mix(b, click, 0, 0.9f);
        float res = 2300 + variant * 380;
        partial(b, 0.001f, 0.08f, res, 0.18f, 0.0005f, 0.012f);
        partial(b, 0.001f, 0.08f, res * 1.47f, 0.10f, 0.0005f, 0.009f);
        lowpass(b, 9000);
        return finish(b, 1.6f);
    }

    /** Airy magical rush with glittering sparkles as the die leaves the hand. */
    static short[] whooshSound() {
        Random r = new Random(5);
        float dur = 0.75f;
        float[] b = buf(dur + 0.9f);
        float[] air = buf(dur);
        noise(air, 0, dur, 1f, 0.25f, 0.0f, r);
        // swell then fade
        for (int i = 0; i < air.length; i++) {
            float k = i / (float) air.length;
            air[i] *= (float) Math.sin(Math.PI * Math.pow(k, 0.7)) * 0.6f;
        }
        sweepLowpass(air, 500, 4200);
        mix(b, air, 0, 0.55f);
        for (int i = 0; i < 14; i++) {
            float t = 0.05f + r.nextFloat() * 0.6f;
            float f = 2600 + r.nextFloat() * 3400;
            partial(b, t, 0.35f, f, 0.05f + r.nextFloat() * 0.05f, 0.002f, 0.07f);
        }
        reverb(b, 0.35f, 0.82f);
        return finish(b, 1.4f);
    }

    /** The die comes to rest: soft thud and a resonant, slightly mysterious bell. */
    static short[] landSound() {
        float[] b = buf(2.0f);
        partial(b, 0, 0.25f, 95, 0.55f, 0.002f, 0.06f);
        bell(b, 0.0f, 587.33f, 0.32f, 1.1f);              // D5
        bell(b, 0.0f, 880f, 0.12f, 0.8f);                 // A5 fifth
        pad(b, 0.0f, 1.2f, new float[]{293.66f, 440f}, 0.07f, 1800);
        reverb(b, 0.4f, 0.85f);
        return finish(b, 1.3f);
    }

    /** Beat the DC: bright rising bells over a warm major chord. */
    static short[] successSound() {
        float[] b = buf(2.4f);
        float[] arp = {659.25f, 880f, 1108.73f, 1318.5f};           // E A C# E
        for (int i = 0; i < arp.length; i++) bell(b, i * 0.075f, arp[i], 0.22f, 0.9f);
        pad(b, 0.05f, 1.4f, new float[]{220f, 277.18f, 329.63f, 440f}, 0.12f, 2600);
        sparkles(b, 0.15f, 0.9f, 10, 0.04f, 11);
        reverb(b, 0.45f, 0.86f);
        return finish(b, 1.3f);
    }

    /** Missed the DC: low muted bells falling into a dark minor. */
    static short[] failureSound() {
        float[] b = buf(2.2f);
        bell(b, 0f, 392f, 0.22f, 0.7f);                 // G4
        bell(b, 0.16f, 311.13f, 0.24f, 0.9f);           // Eb4
        pad(b, 0.1f, 1.2f, new float[]{146.83f, 174.61f, 220f}, 0.12f, 900);   // D minor
        lowpass(b, 3200);
        reverb(b, 0.4f, 0.84f);
        return finish(b, 1.4f);
    }

    /** Natural 20: impact boom, heroic brass-and-choir swell, cascading bells, glitter tail. */
    static short[] critWinSound() {
        Random r = new Random(20);
        float[] b = buf(3.4f);
        boom(b, 0f, 0.9f, 110, 38, 0.8f);
        // brass/choir: detuned saws through an opening filter, C major → G/B → C
        float[] brass = buf(2.2f);
        saws(brass, 0.0f, 1.0f, new float[]{261.63f, 329.63f, 392f, 523.25f}, 0.10f);
        saws(brass, 0.55f, 1.6f, new float[]{261.63f, 392f, 523.25f, 659.25f, 783.99f}, 0.10f);
        sweepLowpass(brass, 600, 3600);
        mix(b, brass, (int) (0.05f * SR), 1f);
        float[] arp = {523.25f, 659.25f, 783.99f, 1046.5f, 1318.5f, 1567.98f, 2093f};
        for (int i = 0; i < arp.length; i++) bell(b, 0.08f + i * 0.07f, arp[i], 0.16f, 1.2f);
        sparkles(b, 0.3f, 1.8f, 26, 0.045f, r.nextInt());
        reverb(b, 0.5f, 0.88f);
        return finish(b, 1.25f);
    }

    /** Natural 1: heavy boom, a crack, and a sinking dissonant drone. */
    static short[] critFailSound() {
        Random r = new Random(1);
        float[] b = buf(3.0f);
        boom(b, 0f, 1.1f, 90, 30, 0.9f);
        float[] crack = buf(0.4f);
        noise(crack, 0, 0.35f, 1f, 0.0005f, 0.05f, r);
        bandpass(crack, 1400, 0.9f);
        mix(b, crack, 0, 0.7f);
        float[] drone = buf(2.4f);
        // tritone that bends downward
        bendSaws(drone, 0.05f, 2.0f, new float[]{110f, 155.56f, 116.54f}, 0.82f, 0.13f);
        sweepLowpass(drone, 1600, 350);
        mix(b, drone, 0, 1f);
        bell(b, 0.05f, 233.08f, 0.18f, 1.2f);
        reverb(b, 0.45f, 0.87f);
        return finish(b, 1.4f);
    }

    static short[] tickSound(boolean bright) {
        Random r = new Random(bright ? 9 : 8);
        float[] b = buf(0.05f);
        noise(b, 0, 0.02f, 0.6f, 0.0002f, 0.002f, r);
        bandpass(b, bright ? 3800 : 2400, 3f);
        partial(b, 0, 0.04f, bright ? 1760 : 1200, 0.25f, 0.0005f, 0.008f);
        return finish(b, 2.2f);
    }

    // ------------------------------------------------------------------ building blocks

    private static float[] buf(float sec) {
        return new float[(int) (sec * SR)];
    }

    /** Sine partial with exponential decay (time constant tau). */
    static void partial(float[] b, float start, float dur, float f, float vol, float attack, float tau) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        double ph = 0, inc = 2 * Math.PI * f / SR;
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = i / (float) SR;
            float env = (attack > 0 ? Math.min(1f, t / attack) : 1f) * (float) Math.exp(-t / tau);
            b[s0 + i] += (float) Math.sin(ph) * env * vol;
            ph += inc;
        }
    }

    /** Inharmonic bell built from classic church-bell partial ratios. */
    static void bell(float[] b, float start, float f, float vol, float length) {
        float[] ratio = {0.5f, 1f, 1.183f, 1.506f, 2f, 2.514f, 2.662f, 3.011f};
        float[] amp = {0.35f, 1f, 0.55f, 0.45f, 0.4f, 0.25f, 0.2f, 0.15f};
        float[] dec = {1.0f, 0.8f, 0.55f, 0.45f, 0.35f, 0.25f, 0.2f, 0.15f};
        for (int i = 0; i < ratio.length; i++) {
            float fr = f * ratio[i];
            if (fr > 12000) continue;
            partial(b, start, length * 2.5f, fr, vol * amp[i] * 0.45f, 0.002f, length * dec[i]);
        }
    }

    /** Soft choir-like pad: slightly detuned triangles and sines with a slow swell. */
    static void pad(float[] b, float start, float dur, float[] notes, float vol, float cutoff) {
        float[] p = buf(dur + 0.05f);
        int n = (int) (dur * SR);
        for (float f : notes) {
            for (int d = -1; d <= 1; d++) {
                double inc = 2 * Math.PI * f * (1 + d * 0.004) / SR, ph = d * 1.3;
                double vib = 0;
                for (int i = 0; i < n; i++) {
                    float k = i / (float) n;
                    float env = (float) (Math.sin(Math.PI * Math.min(1, k * 2.2)) * Math.pow(1 - k, 0.8));
                    vib += 2 * Math.PI * 5.2 / SR;
                    double s = Math.sin(ph) + 0.25 * Math.sin(2 * ph);
                    p[i] += (float) (s * env) * vol / notes.length;
                    ph += inc * (1 + 0.003 * Math.sin(vib));
                }
            }
        }
        lowpass(p, cutoff);
        mix(b, p, (int) (start * SR), 1f);
    }

    /** Detuned sawtooth chord with a brassy attack. */
    static void saws(float[] b, float start, float dur, float[] notes, float vol) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        for (float f : notes) {
            for (int d = -1; d <= 1; d++) {
                double ph = (d + 1) * 0.31, inc = f * (1 + d * 0.006) / SR;
                for (int i = 0; i < n && s0 + i < b.length; i++) {
                    float t = i / (float) SR, k = i / (float) n;
                    float env = Math.min(1f, t / 0.06f) * (float) Math.pow(1 - k, 1.4);
                    b[s0 + i] += (float) (2 * ph - 1) * env * vol / notes.length;
                    ph += inc;
                    ph -= Math.floor(ph);
                }
            }
        }
    }

    /** Saw chord whose pitch sinks by the factor bend over its length. */
    static void bendSaws(float[] b, float start, float dur, float[] notes, float bend, float vol) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        for (float f : notes) {
            for (int d = -1; d <= 1; d += 2) {
                double ph = 0;
                for (int i = 0; i < n && s0 + i < b.length; i++) {
                    float t = i / (float) SR, k = i / (float) n;
                    double fr = f * (1 + d * 0.008) * Math.pow(bend, k);
                    float env = Math.min(1f, t / 0.15f) * (float) Math.pow(1 - k, 1.2);
                    b[s0 + i] += (float) (2 * ph - 1) * env * vol / notes.length;
                    ph += fr / SR;
                    ph -= Math.floor(ph);
                }
            }
        }
    }

    /** Low sine with a falling pitch: a cinematic hit. */
    static void boom(float[] b, float start, float dur, float f0, float f1, float vol) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        double ph = 0;
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = i / (float) SR, k = i / (float) n;
            double f = f0 * Math.pow(f1 / f0, Math.min(1, k * 1.6));
            float env = Math.min(1f, t / 0.004f) * (float) Math.exp(-t / (dur * 0.3f));
            b[s0 + i] += (float) Math.tanh(Math.sin(ph) * 1.6) * env * vol;
            ph += 2 * Math.PI * f / SR;
        }
    }

    static void sparkles(float[] b, float start, float spread, int count, float vol, int seed) {
        Random r = new Random(seed);
        for (int i = 0; i < count; i++) {
            float t = start + (float) Math.pow(r.nextFloat(), 1.5) * spread;
            float f = 3000 + r.nextFloat() * 4500;
            partial(b, t, 0.3f, f, vol * (0.5f + r.nextFloat()), 0.001f, 0.05f + r.nextFloat() * 0.05f);
        }
    }

    static void noise(float[] b, float start, float dur, float vol, float attack, float tau, Random r) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = i / (float) SR;
            float env = (attack > 0 ? Math.min(1f, t / attack) : 1f) * (tau > 0 ? (float) Math.exp(-t / tau) : 1f);
            b[s0 + i] += (r.nextFloat() * 2 - 1) * env * vol;
        }
    }

    static void mix(float[] dst, float[] src, int offset, float gain) {
        for (int i = 0; i < src.length && offset + i < dst.length; i++) dst[offset + i] += src[i] * gain;
    }

    static void lowpass(float[] b, float cutoff) {
        float rc = 1f / (2f * (float) Math.PI * cutoff), dt = 1f / SR, a = dt / (rc + dt);
        float y1 = 0, y2 = 0;
        for (int i = 0; i < b.length; i++) {   // two poles for a gentler, darker slope
            y1 += a * (b[i] - y1);
            y2 += a * (y1 - y2);
            b[i] = y2;
        }
    }

    /** One-pole pair low-pass whose cutoff glides from c0 to c1 over the buffer. */
    static void sweepLowpass(float[] b, float c0, float c1) {
        float y1 = 0, y2 = 0, dt = 1f / SR;
        for (int i = 0; i < b.length; i++) {
            float k = i / (float) b.length;
            float c = (float) (c0 * Math.pow(c1 / c0, k));
            float rc = 1f / (2f * (float) Math.PI * c), a = dt / (rc + dt);
            y1 += a * (b[i] - y1);
            y2 += a * (y1 - y2);
            b[i] = y2;
        }
    }

    /** RBJ band-pass biquad. */
    static void bandpass(float[] b, float f, float q) {
        double w0 = 2 * Math.PI * f / SR, alpha = Math.sin(w0) / (2 * q), cs = Math.cos(w0);
        double a0 = 1 + alpha;
        double b0 = alpha / a0, b2 = -alpha / a0, a1 = -2 * cs / a0, a2 = (1 - alpha) / a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < b.length; i++) {
            double x = b[i];
            double y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y;
            b[i] = (float) y;
        }
    }

    /** Freeverb-style hall: 6 damped combs in parallel, then 3 all-passes. */
    static void reverb(float[] b, float wet, float room) {
        int[] combs = {1557, 1617, 1491, 1422, 1277, 1356};
        int[] aps = {556, 441, 341};
        float damp = 0.35f;
        float[] out = new float[b.length];
        for (int d : combs) {
            float[] line = new float[d];
            int p = 0;
            float store = 0;
            for (int i = 0; i < b.length; i++) {
                float y = line[p];
                store = y * (1 - damp) + store * damp;
                line[p] = b[i] * 0.015f + store * room;
                out[i] += y;
                if (++p >= d) p = 0;
            }
        }
        for (int d : aps) {
            float[] line = new float[d];
            int p = 0;
            for (int i = 0; i < out.length; i++) {
                float bufout = line[p];
                float y = -out[i] + bufout;
                line[p] = out[i] + bufout * 0.5f;
                out[i] = y;
                if (++p >= d) p = 0;
            }
        }
        for (int i = 0; i < b.length; i++) b[i] = b[i] * (1 - wet * 0.5f) + out[i] * wet * 3f;
    }

    static short[] finish(float[] b, float gain) {
        float peak = 1e-6f;
        for (float v : b) peak = Math.max(peak, Math.abs(v));
        float norm = Math.min(gain, 0.98f / peak * 1.15f);
        short[] out = new short[b.length];
        int fade = Math.min(b.length, SR / 50);
        for (int i = 0; i < b.length; i++) {
            float v = (float) Math.tanh(b[i] * norm) * 0.95f;
            if (i >= b.length - fade) v *= (b.length - i) / (float) fade;
            out[i] = (short) (v * 32767);
        }
        return out;
    }

    static void writeWav(File f, short[] pcm) throws IOException {
        int dataLen = pcm.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen)
                .put(new byte[]{'W', 'A', 'V', 'E'})
                .put(new byte[]{'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) 1)
                .putInt(SR).putInt(SR * 2)
                .putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        for (short s : pcm) bb.putShort(s);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(bb.array());
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }
}
