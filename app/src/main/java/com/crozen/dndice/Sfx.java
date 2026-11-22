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
    private static final String VERSION = "sfx5";

    private final SoundPool pool;
    private final Vibrator vib;
    private final Random rng = new Random();
    private final int[] clack = new int[4];
    private volatile int whoosh, flight, land, wisp, ding, success, failure, critWin, critFail, tick, select;
    private int flightStream;
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
            clack[i] = load(dir, "clink" + i, () -> clinkSound(k));
        }
        whoosh = load(dir, "whoosh", Sfx::whooshSound);
        flight = load(dir, "flight", Sfx::flightSound);
        land = load(dir, "land", Sfx::landSound);
        wisp = load(dir, "wisp", Sfx::wispSound);
        ding = load(dir, "ding", Sfx::dingSound);
        success = load(dir, "success", Sfx::successSound);
        failure = load(dir, "failure", Sfx::failureSound);
        critWin = load(dir, "critwin", Sfx::critWinSound);
        critFail = load(dir, "critfail", Sfx::critFailSound);
    }

    // ------------------------------------------------------------------ public API

    void throwStart() {
        play(whoosh, 0.8f, 1f);
    }

    /** The die enters the panel: airy magical ringing while it flies. */
    void flightStart() {
        flightStream = play(flight, 0.6f, 0.96f + rng.nextFloat() * 0.08f);
    }

    void stopFlight() {
        if (flightStream != 0) pool.stop(flightStream);
        flightStream = 0;
    }

    /** The die hits a wall of the panel; strength 0..1. */
    void impact(float strength) {
        int id = clack[rng.nextInt(clack.length)];
        play(id, 0.3f + 0.7f * strength, 0.92f + rng.nextFloat() * 0.18f);
        buzz(6 + (int) (12 * strength), (int) (50 + 150 * strength));
    }

    void landed() {
        play(land, 1f, 1f);
        buzz(30, 200);
    }

    /** Golden spark leaves the die. */
    void wisp() {
        play(wisp, 0.55f, 1f);
    }

    /** Spark reaches the DC / total. */
    void ding() {
        play(ding, 0.85f, 1f);
        buzz(12, 120);
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
        pattern(new long[]{0, 60, 160, 220, 90, 300}, new int[]{0, 120, 0, 255, 0, 200});
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

    private int play(int id, float vol, float rate) {
        if (soundOn && id != 0) return pool.play(id, vol, vol, 1, 0, rate);
        return 0;
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

    /** A crystal die striking the stone rim of the panel: a click and a glassy, inharmonic ring. */
    static short[] clinkSound(int variant) {
        Random r = new Random(200 + variant);
        float[] b = buf(0.3f);
        float f = 1900 + variant * 260;
        partial(b, 0, 0.3f, f, 0.30f, 0.0004f, 0.075f);
        partial(b, 0, 0.3f, f * 2.76f, 0.22f, 0.0004f, 0.045f);
        partial(b, 0, 0.2f, f * 3.42f, 0.14f, 0.0004f, 0.03f);
        partial(b, 0, 0.12f, 5600 + variant * 380, 0.18f, 0.0003f, 0.02f);
        partial(b, 0, 0.15f, 120 + variant * 15, 0.25f, 0.001f, 0.02f);
        float[] click = buf(0.03f);
        noise(click, 0, 0.02f, 1f, 0.0001f, 0.0025f, r);
        bandpass(click, 6200, 1.6f);
        mix(b, click, 0, 0.9f);
        reverb(b, 0.18f, 0.7f);
        return finish(b, 1f);
    }

    /** Throw: a bright sparkle and a quick airy rush as the die leaves. */
    static short[] whooshSound() {
        Random r = new Random(5);
        float dur = 0.45f;
        float[] b = buf(dur + 0.8f);
        float[] air = buf(dur);
        noise(air, 0, dur, 1f, 0.06f, 0.0f, r);
        for (int i = 0; i < air.length; i++) {
            float k = i / (float) air.length;
            air[i] *= (float) Math.sin(Math.PI * Math.pow(k, 0.5)) * 0.7f;
        }
        sweepLowpass(air, 1200, 7000);
        mix(b, air, 0, 0.6f);
        partial(b, 0, 0.4f, 9700, 0.12f, 0.001f, 0.08f);
        partial(b, 0.01f, 0.4f, 4380, 0.10f, 0.001f, 0.09f);
        sparkles(b, 0.0f, 0.3f, 8, 0.05f, 3);
        reverb(b, 0.35f, 0.8f);
        return finish(b, 1f);
    }

    /** While the die flies: shimmering 2–3 kHz ringing with tinkles, fading as it slows. */
    static short[] flightSound() {
        Random r = new Random(77);
        float dur = 1.6f;
        float[] b = buf(dur + 0.8f);
        float[] air = buf(dur);
        noise(air, 0, dur, 1f, 0.05f, 0.6f, r);
        bandpass(air, 2600, 1.2f);
        mix(b, air, 0, 0.25f);
        float[] tones = {1957, 2016, 2320, 2965, 3023};
        for (float f : tones) partial(b, 0.02f, dur, f, 0.05f, 0.12f, 0.55f);
        for (int i = 0; i < 26; i++) {
            float t = (float) Math.pow(r.nextFloat(), 1.3) * (dur - 0.2f);
            float f = r.nextBoolean() ? 2000 + r.nextFloat() * 1100 : 5400 + r.nextFloat() * 1600;
            partial(b, t, 0.25f, f, 0.035f + r.nextFloat() * 0.04f, 0.001f, 0.04f + r.nextFloat() * 0.05f);
        }
        reverb(b, 0.4f, 0.82f);
        return finish(b, 0.8f);
    }

    /** The die comes to rest: deep thud with a low boom and a short glitter. */
    static short[] landSound() {
        Random r = new Random(9);
        float[] b = buf(1.4f);
        boom(b, 0f, 0.5f, 85, 48, 0.9f);
        partial(b, 0, 0.2f, 160, 0.35f, 0.001f, 0.04f);
        float[] knock = buf(0.08f);
        noise(knock, 0, 0.06f, 1f, 0.0002f, 0.012f, r);
        bandpass(knock, 700, 1.0f);
        mix(b, knock, 0, 0.7f);
        partial(b, 0.0f, 0.4f, 5480, 0.05f, 0.001f, 0.06f);
        sparkles(b, 0.02f, 0.25f, 7, 0.035f, 4);
        reverb(b, 0.3f, 0.8f);
        return finish(b, 1f);
    }

    /** A golden spark rising from the die: upward glittering sweep. */
    static short[] wispSound() {
        Random r = new Random(31);
        float dur = 0.5f;
        float[] b = buf(dur + 0.7f);
        float[] air = buf(dur);
        noise(air, 0, dur, 1f, 0.15f, 0f, r);
        for (int i = 0; i < air.length; i++) air[i] *= (float) Math.sin(Math.PI * i / air.length) * 0.5f;
        sweepLowpass(air, 3000, 12000);
        mix(b, air, 0, 0.3f);
        for (int i = 0; i < 12; i++) {
            float t = i * dur / 12f;
            partial(b, t, 0.2f, 3500 + i * 320 + r.nextFloat() * 200, 0.05f, 0.001f, 0.05f);
        }
        reverb(b, 0.4f, 0.8f);
        return finish(b, 0.7f);
    }

    /** The spark hits the DC: a bright, slightly beating bell. */
    static short[] dingSound() {
        float[] b = buf(1.8f);
        partial(b, 0, 1.6f, 1465, 0.35f, 0.001f, 0.6f);
        partial(b, 0, 1.6f, 1559, 0.25f, 0.001f, 0.5f);
        partial(b, 0, 1.0f, 2930, 0.12f, 0.001f, 0.25f);
        partial(b, 0, 0.8f, 4395, 0.06f, 0.001f, 0.12f);
        partial(b, 0, 1.2f, 733, 0.10f, 0.002f, 0.4f);
        reverb(b, 0.4f, 0.84f);
        return finish(b, 1f);
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

    /** Rising broadband rush that ends in the hit at time end. */
    static void riser(float[] b, float end, float len, float vol, int seed) {
        Random r = new Random(seed);
        float[] n = buf(len + 0.6f);
        int hit = (int) (len * SR);
        for (int i = 0; i < n.length; i++) {
            float env = i < hit ? (float) Math.pow(i / (float) hit, 2.2) : (float) Math.exp(-(i - hit) / (0.18f * SR));
            n[i] = (r.nextFloat() * 2 - 1) * env;
        }
        sweepLowpass(n, 600, 9000);
        mix(b, n, (int) ((end - len) * SR), vol);
    }

    /** Natural 20: rush, impact, heroic brass-and-choir swell, cascading bells, glitter tail. */
    static short[] critWinSound() {
        Random r = new Random(20);
        float[] b = buf(3.6f);
        riser(b, 0.3f, 0.3f, 0.6f, 21);
        boom(b, 0.3f, 0.9f, 110, 38, 0.8f);
        float[] brass = buf(2.2f);
        saws(brass, 0.0f, 1.0f, new float[]{261.63f, 329.63f, 392f, 523.25f}, 0.10f);
        saws(brass, 0.55f, 1.6f, new float[]{261.63f, 392f, 523.25f, 659.25f, 783.99f}, 0.10f);
        sweepLowpass(brass, 600, 3600);
        mix(b, brass, (int) (0.33f * SR), 1f);
        pad(b, 0.35f, 2.4f, new float[]{261.63f, 329.63f, 392f, 523.25f}, 0.12f, 2400);
        float[] arp = {523.25f, 659.25f, 783.99f, 1046.5f, 1318.5f, 1567.98f, 2093f};
        for (int i = 0; i < arp.length; i++) bell(b, 0.38f + i * 0.07f, arp[i], 0.16f, 1.2f);
        sparkles(b, 0.6f, 1.8f, 26, 0.045f, r.nextInt());
        reverb(b, 0.5f, 0.88f);
        return finish(b, 1f);
    }

    /** Natural 1: roaring rush into a heavy hit and crackle, then a dark low drone (E–B–G). */
    static short[] critFailSound() {
        Random r = new Random(1);
        float[] b = buf(4.4f);
        riser(b, 0.35f, 0.35f, 0.9f, 2);
        boom(b, 0.35f, 1.2f, 95, 30, 1f);
        float[] crack = buf(0.6f);
        noise(crack, 0, 0.5f, 1f, 0.0005f, 0.09f, r);
        bandpass(crack, 1300, 0.7f);
        mix(b, crack, (int) (0.35f * SR), 0.8f);
        // fire crackle
        for (int i = 0; i < 40; i++) {
            float t = 0.4f + r.nextFloat() * 1.0f;
            float[] c = buf(0.02f);
            noise(c, 0, 0.015f, 1f, 0.0001f, 0.003f, r);
            bandpass(c, 2500 + r.nextFloat() * 3000, 2f);
            mix(b, c, (int) (t * SR), 0.25f * (1.4f - (t - 0.4f)));
        }
        // dark drone: E2, E3, B3, G3, E4 with slow swell
        pad(b, 0.9f, 3.4f, new float[]{82.41f, 164.81f, 246.94f, 196f, 329.63f}, 0.35f, 700);
        bendSaws(b, 0.5f, 1.4f, new float[]{164.81f, 233.08f}, 0.9f, 0.06f);
        reverb(b, 0.45f, 0.87f);
        return finish(b, 1f);
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
        float norm = 0.95f / peak * Math.min(1f, gain);  // every sound peaks at the same level
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
