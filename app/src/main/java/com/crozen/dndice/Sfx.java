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
 * All sounds are synthesized in code (no audio assets), themed after Drakkenheim: singing glass,
 * a glass harmonica, an "aah" choir and whispers of the Haze, all through a small hall reverb.
 * They are rendered once on a background thread, cached as WAV and played with SoundPool.
 */
final class Sfx {

    static final int SR = 44100;
    private static final String VERSION = "sfx6";

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

    // ------------------------------------------------------------------ sound design (Drakkenheim)
    //
    // Eerie and crystalline: singing glass, a glass harmonica, an "aah" choir, whispers of the Haze,
    // and for a natural 1 a corrupted growl with cracking crystal.

    /** Bezel tick / UI select: a tiny crystal shard tapped with a fingernail. */
    static short[] tickSound(boolean bright) {
        Random r = new Random(bright ? 61 : 60);
        float[] b = buf(0.25f);
        float f = bright ? 2637 : 3520;
        glassPing(b, 0, f, 0.35f, 0.05f);
        if (bright) glassPing(b, 0.045f, f * 1.335f, 0.25f, 0.07f);   // a fourth up: "selected"
        float[] c = buf(0.01f);
        noise(c, 0, 0.006f, 0.6f, 0.0001f, 0.0015f, r);
        bandpass(c, 7000, 2f);
        mix(b, c, 0, 1f);
        reverb(b, 0.2f, 0.72f);
        return finish(b, 1f);
    }

    /** The die striking stone: a dull knock plus a shard of delerium ringing on a whole-tone pitch. */
    static short[] clinkSound(int variant) {
        Random r = new Random(300 + variant);
        float[] b = buf(0.45f);
        float[] tones = {1661, 1865, 2093, 2349};            // G#6 A#6 C7 D7: whole-tone, uneasy
        float f = tones[variant % tones.length];
        partial(b, 0, 0.45f, f, 0.28f, 0.0003f, 0.12f);
        partial(b, 0, 0.45f, f * 2.41f, 0.16f, 0.0003f, 0.06f);
        partial(b, 0, 0.3f, f * 3.87f, 0.08f, 0.0003f, 0.03f);
        partial(b, 0.002f, 0.45f, f * 1.006f, 0.12f, 0.0003f, 0.1f);   // beating against the root
        float[] knock = buf(0.06f);
        noise(knock, 0, 0.05f, 1f, 0.0002f, 0.008f, r);
        bandpass(knock, 450 + variant * 60, 1.3f);
        mix(b, knock, 0, 0.9f);
        partial(b, 0, 0.08f, 95 + variant * 8, 0.35f, 0.001f, 0.018f);
        reverb(b, 0.32f, 0.8f);
        return finish(b, 1f);
    }

    /** Throw: the Haze inhales — a reversed breath swelling into a glassy flourish. */
    static short[] whooshSound() {
        Random r = new Random(7);
        float[] b = buf(1.1f);
        float[] breath = buf(0.4f);
        noise(breath, 0, 0.4f, 1f, 0.002f, 0.12f, r);
        bandpass(breath, 1800, 0.7f);
        reverse(breath);                                      // swells instead of decaying
        mix(b, breath, 0, 0.7f);
        whisper(b, 0.0f, 0.4f, 0.25f, 71);
        // quick upward glass glissando
        glide(b, 0.22f, 0.25f, 880, 2640, 0.12f);
        partial(b, 0, 0.4f, 55, 0.0f, 0.2f, 0.2f);
        reverb(b, 0.45f, 0.84f);
        return finish(b, 1f);
    }

    /** While the die flies: the crystal sings — a beating glass drone with whispers underneath. */
    static short[] flightSound() {
        Random r = new Random(91);
        float dur = 1.8f;
        float[] b = buf(dur + 0.8f);
        // singing-glass pair, slightly detuned so it throbs, fading as the die slows
        float[] sing = buf(dur);
        double p1 = 0, p2 = 0, p3 = 0;
        for (int i = 0; i < sing.length; i++) {
            float t = i / (float) SR, k = t / dur;
            double bend = 1 + 0.03 * Math.sin(2 * Math.PI * 0.7 * t);
            p1 += 2 * Math.PI * 1318.5 * bend / SR;
            p2 += 2 * Math.PI * 1322.5 * bend / SR;
            p3 += 2 * Math.PI * 1975.5 * bend / SR;
            float env = Math.min(1f, t / 0.12f) * (float) Math.pow(1 - k, 1.6);
            sing[i] = (float) (Math.sin(p1) * 0.4 + Math.sin(p2) * 0.35 + Math.sin(p3) * 0.18) * env;
        }
        mix(b, sing, 0, 0.35f);
        whisper(b, 0.05f, dur * 0.8f, 0.3f, 92);
        for (int i = 0; i < 14; i++) {
            float t = r.nextFloat() * (dur - 0.3f);
            glassPing(b, t, 2600 + r.nextFloat() * 2600, 0.05f + r.nextFloat() * 0.04f, 0.06f);
        }
        reverb(b, 0.45f, 0.85f);
        return finish(b, 0.75f);
    }

    /** The die comes to rest: a dark gong-like strike and crystal dust settling. */
    static short[] landSound() {
        Random r = new Random(13);
        float[] b = buf(2.2f);
        boom(b, 0f, 0.6f, 70, 42, 0.9f);
        // inharmonic metallic partials of a small gong
        float[] gong = {182, 297, 455, 612, 871};
        for (int i = 0; i < gong.length; i++) partial(b, 0, 2f, gong[i], 0.18f / (1 + i * 0.4f), 0.002f, 0.9f / (1 + i * 0.5f));
        float[] knock = buf(0.08f);
        noise(knock, 0, 0.06f, 1f, 0.0002f, 0.012f, r);
        bandpass(knock, 600, 1f);
        mix(b, knock, 0, 0.7f);
        shatter(b, 0.03f, 0.5f, 10, 0.04f, 14);
        reverb(b, 0.4f, 0.86f);
        return finish(b, 1f);
    }

    /** A mote of delerium rising from the die: a glassy glissando with vibrato and glitter. */
    static short[] wispSound() {
        float[] b = buf(1.3f);
        glide(b, 0f, 0.48f, 620, 2480, 0.22f);
        glide(b, 0.02f, 0.48f, 930, 3720, 0.08f);
        whisper(b, 0f, 0.45f, 0.12f, 33);
        sparkles(b, 0.1f, 0.4f, 8, 0.03f, 34);
        reverb(b, 0.45f, 0.84f);
        return finish(b, 0.7f);
    }

    /** The mote reaches the DC: a glass-harmonica tone, pure and a little unsettling. */
    static short[] dingSound() {
        float[] b = buf(2.4f);
        glass(b, 0f, 1.9f, 1760, 0.35f, 0.008f);
        glass(b, 0f, 1.9f, 1764.5f, 0.2f, 0.008f);           // slow beating
        glass(b, 0.01f, 1.4f, 2637, 0.12f, 0.01f);
        reverb(b, 0.5f, 0.87f);
        return finish(b, 1f);
    }

    /** Beat the DC: a rising Lydian glass chord over an "aah" choir. */
    static short[] successSound() {
        float[] b = buf(3.0f);
        float[] notes = {523.25f, 659.25f, 739.99f, 987.77f, 1318.5f};   // C E F# B E — bright, strange
        for (int i = 0; i < notes.length; i++) glass(b, i * 0.07f, 2.2f, notes[i], 0.16f, 0.03f);
        choir(b, 0.05f, 2.2f, new float[]{261.63f, 329.63f, 392f, 493.88f}, 0.22f, 0);
        sparkles(b, 0.2f, 1.0f, 10, 0.03f, 41);
        reverb(b, 0.55f, 0.88f);
        return finish(b, 1f);
    }

    /** Missed the DC: descending glass over a low choir on a dark minor second. */
    static short[] failureSound() {
        float[] b = buf(2.8f);
        glass(b, 0f, 1.4f, 880f, 0.2f, 0.01f);
        glass(b, 0.22f, 1.6f, 830.6f, 0.2f, 0.01f);
        glass(b, 0.44f, 2.0f, 622.25f, 0.22f, 0.01f);
        choir(b, 0.1f, 2.4f, new float[]{146.83f, 155.56f, 220f}, 0.25f, 1);
        lowpass(b, 4200);
        reverb(b, 0.5f, 0.87f);
        return finish(b, 1f);
    }

    /** Natural 20: a reversed shimmer rushes in, then radiant glass cascades over a heavenly choir. */
    static short[] critWinSound() {
        float[] b = buf(4.2f);
        float[] swell = buf(0.45f);
        Random r = new Random(201);
        noise(swell, 0, 0.45f, 1f, 0.001f, 0.15f, r);
        bandpass(swell, 6000, 0.8f);
        reverse(swell);
        mix(b, swell, 0, 0.6f);
        boom(b, 0.45f, 1.0f, 98, 40, 0.75f);
        float[] up = {659.25f, 739.99f, 880f, 987.77f, 1174.66f, 1318.5f, 1479.98f, 1760f, 1975.5f, 2349.3f};
        for (int i = 0; i < up.length; i++) glass(b, 0.45f + i * 0.055f, 1.8f, up[i], 0.13f, 0.004f);
        choir(b, 0.45f, 3.2f, new float[]{220f, 277.18f, 329.63f, 415.3f, 554.37f}, 0.32f, 0);
        sparkles(b, 0.5f, 2.2f, 34, 0.04f, 202);
        reverb(b, 0.55f, 0.9f);
        return finish(b, 1f);
    }

    /** Natural 1: corruption — a reversed rush, a crack of crystal, a warped growl and a sick drone. */
    static short[] critFailSound() {
        Random r = new Random(101);
        float[] b = buf(4.6f);
        float[] swell = buf(0.4f);
        noise(swell, 0, 0.4f, 1f, 0.001f, 0.14f, r);
        lowpass(swell, 2500);
        reverse(swell);
        mix(b, swell, 0, 0.8f);
        boom(b, 0.4f, 1.4f, 80, 26, 1f);
        shatter(b, 0.4f, 0.35f, 22, 0.09f, 102);
        float[] crack = buf(0.3f);
        noise(crack, 0, 0.25f, 1f, 0.0003f, 0.04f, r);
        bandpass(crack, 2200, 0.8f);
        mix(b, crack, (int) (0.4f * SR), 0.7f);
        growl(b, 0.45f, 2.2f, 73.4f, 0.35f);
        whisper(b, 0.6f, 2.6f, 0.22f, 103);
        choir(b, 0.9f, 3.4f, new float[]{110f, 116.54f, 155.56f}, 0.3f, 1);   // A, Bb, Eb: cluster + tritone
        reverb(b, 0.5f, 0.88f);
        return finish(b, 1f);
    }

    // ------------------------------------------------------------------ building blocks

    /** Short glassy ping: root plus inharmonic overtones. */
    static void glassPing(float[] b, float start, float f, float vol, float tau) {
        partial(b, start, tau * 6, f, vol, 0.0003f, tau);
        partial(b, start, tau * 4, f * 2.76f, vol * 0.45f, 0.0003f, tau * 0.6f);
        partial(b, start, tau * 3, f * 5.4f, vol * 0.2f, 0.0003f, tau * 0.35f);
    }

    /** Glass-harmonica tone: soft attack, pure partials, gentle tremolo, long ring. */
    static void glass(float[] b, float start, float dur, float f, float vol, float attack) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        double ph = 0, ph2 = 0, ph3 = 0;
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = i / (float) SR, k = i / (float) n;
            float env = Math.min(1f, t / Math.max(attack, 0.001f)) * (float) Math.pow(1 - k, 1.3);
            float trem = 1 + 0.08f * (float) Math.sin(2 * Math.PI * 5.3 * t);
            ph += 2 * Math.PI * f / SR;
            ph2 += 2 * Math.PI * f * 2 / SR;
            ph3 += 2 * Math.PI * f * 3 / SR;
            b[s0 + i] += (float) (Math.sin(ph) + 0.22 * Math.sin(ph2) + 0.06 * Math.sin(ph3)) * env * trem * vol;
        }
    }

    /** Sine glissando from f0 to f1 with vibrato. */
    static void glide(float[] b, float start, float dur, float f0, float f1, float vol) {
        int s0 = (int) (start * SR), n = (int) (dur * SR);
        double ph = 0;
        for (int i = 0; i < n && s0 + i < b.length; i++) {
            float t = i / (float) SR, k = i / (float) n;
            double f = f0 * Math.pow(f1 / f0, k) * (1 + 0.012 * Math.sin(2 * Math.PI * 7 * t));
            ph += 2 * Math.PI * f / SR;
            float env = (float) Math.sin(Math.PI * Math.min(1, k * 1.1));
            b[s0 + i] += (float) Math.sin(ph) * env * vol;
        }
    }

    /**
     * "Aah" choir: detuned saw voices through two vowel formants with a slow swell.
     * vowel 0 = "ah" (bright), 1 = "oh" (dark).
     */
    static void choir(float[] b, float start, float dur, float[] notes, float vol, int vowel) {
        float[] v = buf(dur);
        int n = v.length;
        for (float f : notes) {
            for (int d = -1; d <= 1; d++) {
                double ph = (d + 1) * 0.37, vib = d * 1.1;
                for (int i = 0; i < n; i++) {
                    vib += 2 * Math.PI * (5 + d * 0.4) / SR;
                    double fr = f * (1 + d * 0.005) * (1 + 0.004 * Math.sin(vib));
                    ph += fr / SR;
                    ph -= Math.floor(ph);
                    float k = i / (float) n;
                    float env = (float) (Math.min(1, k * 3.5) * Math.pow(1 - k, 0.9));
                    v[i] += (float) (2 * ph - 1) * env / notes.length;
                }
            }
        }
        float[] f1 = v.clone(), f2 = v.clone();
        bandpass(f1, vowel == 0 ? 780 : 450, 4f);
        bandpass(f2, vowel == 0 ? 1180 : 800, 5f);
        for (int i = 0; i < n; i++) v[i] = f1[i] + f2[i] * 0.6f;
        mix(b, v, (int) (start * SR), vol * 2.2f);
    }

    /** Whispers of the Haze: band-passed noise chopped into syllable-like bursts. */
    static void whisper(float[] b, float start, float dur, float vol, int seed) {
        Random r = new Random(seed);
        float[] w = buf(dur);
        noise(w, 0, dur, 1f, 0, 0, r);
        bandpass(w, 2400, 1.1f);
        double ph = 0;
        float rate = 7 + r.nextFloat() * 3;
        for (int i = 0; i < w.length; i++) {
            float k = i / (float) w.length;
            ph += 2 * Math.PI * rate * (1 + 0.3 * Math.sin(i * 0.00007)) / SR;
            float syl = (float) Math.max(0, Math.sin(ph)) ;
            w[i] *= syl * syl * (float) Math.sin(Math.PI * k);
        }
        mix(b, w, (int) (start * SR), vol);
    }

    /** Shattering crystal: a spray of very short high pings. */
    static void shatter(float[] b, float start, float spread, int count, float vol, int seed) {
        Random r = new Random(seed);
        for (int i = 0; i < count; i++) {
            float t = start + (float) Math.pow(r.nextFloat(), 2) * spread;
            glassPing(b, t, 3000 + r.nextFloat() * 5000, vol * (0.4f + r.nextFloat()), 0.015f + r.nextFloat() * 0.03f);
        }
    }

    /** Corrupted growl: detuned saws sinking in pitch, wave-shaped and darkened. */
    static void growl(float[] b, float start, float dur, float f, float vol) {
        float[] g = buf(dur);
        for (int d = -2; d <= 2; d++) {
            double ph = d * 0.21;
            for (int i = 0; i < g.length; i++) {
                float k = i / (float) g.length;
                double fr = f * (1 + d * 0.011) * Math.pow(0.7, k) * (1 + 0.04 * Math.sin(2 * Math.PI * 9 * i / (double) SR));
                ph += fr / SR;
                ph -= Math.floor(ph);
                float env = Math.min(1f, k * 8) * (float) Math.pow(1 - k, 1.3);
                g[i] += (float) (2 * ph - 1) * env * 0.25f;
            }
        }
        for (int i = 0; i < g.length; i++) g[i] = (float) Math.tanh(g[i] * 3) * 0.6f;
        lowpass(g, 900);
        mix(b, g, (int) (start * SR), vol);
    }

    static void reverse(float[] b) {
        for (int i = 0, j = b.length - 1; i < j; i++, j--) {
            float t = b[i];
            b[i] = b[j];
            b[j] = t;
        }
    }


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
