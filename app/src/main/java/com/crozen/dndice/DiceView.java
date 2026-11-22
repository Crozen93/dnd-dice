package com.crozen.dndice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.MotionEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * The whole app on one Canvas, themed after the ruined city of Drakkenheim: a gothic lancet
 * window in an iron frame with a delerium crystal at its peak, the Haze drifting across the
 * screen, and dice cut from iridescent delerium (or obsidian). The roll follows the BG3
 * ability-check flow: Difficulty Class on top, the die below.
 *
 * A roll: the die vanishes, flies back in from off-screen and ricochets off the panel walls
 * with spark trails, lands with a thud, a golden spark carries the result to the DC, and the
 * verdict plays out (a fiery streak and smoke on a natural 1, a golden burst on a natural 20).
 */
final class DiceView extends View implements SensorEventListener {

    // ------------------------------------------------------------------ state

    private static final int IDLE = 0, ROLLING = 1, RESULT = 2;
    private int state = IDLE;
    private boolean historyOpen, settingsOpen, clearConfirm;

    private final Roller roller;
    private final Sfx sfx;
    private final Random rng = new Random();
    private final Poly[] polys = new Poly[8];
    private final Path[][] labelPaths = new Path[8][];

    private int active = Roller.P_DIE;
    private float S, cx, cy;

    private long lastNs;
    private float now;
    /** Test hook: when >= 0, used instead of the system clock (desktop preview renders). */
    static long clockOverrideNs = -1;
    private boolean running;

    // smoothed transition values
    private float chipsA = 1, idleA = 1, rollA, histX = 1, settingsA, flashA, redA;
    private float idleSwapAt = -10, shakeAt = -10;

    // ------------------------------------------------------------------ layout (fractions of S)

    private static final float ARCH_L = 0.19f, ARCH_R = 0.81f, ARCH_T = 0.09f, ARCH_B = 0.835f;
    private static final float HEAD_CAP_Y = 0.215f, HEAD_NUM_Y = 0.318f, DIVIDER_Y = 0.372f;
    private static final float DIE_Y = 0.468f, DIE_SIZE = 0.15f;
    private static final float BREAK_Y = 0.668f, TOTAL_Y = 0.75f, VERDICT_Y = 0.81f;
    private static final float BOX_L = 0.31f, BOX_R = 0.69f, BOX_T = 0.30f, BOX_B = 0.63f;

    // ------------------------------------------------------------------ roll timeline (seconds)

    private static final float VANISH = 0.26f, F0 = 0.28f, FD = 1.6f, BLEND = 0.5f;
    private static final float WISP_T = 0.42f, WISP_D = 0.42f, DING_T = WISP_T + WISP_D, VERDICT_T = 1.08f;
    private static final int PATH_HZ = 240, PATH_N = (int) (FD * PATH_HZ) + 2;

    private static final class Anim {
        int kind, faceIdx;
        Poly poly;
        final Quat qStart = new Quat(), qFinal = new Quat(), delta = new Quat(), q = new Quat();
        final float[] ax1 = new float[3], ax2 = new float[3];
        float a1, a2, delay, tx, ty, size;
        boolean dropped;
        final float[] px = new float[PATH_N], py = new float[PATH_N];
        float x, y, sz, speed;
    }

    private final Anim[] anims = new Anim[Roller.MAX_COUNT];
    private int animCount;
    private Roller.Result result;
    private boolean fromIdle;
    private final Quat vanishQ = new Quat();
    private float rollT0, revealT0, landAt;
    private boolean flightPlayed, wispPlayed, dingPlayed, verdictPlayed;
    private final float[] impT = new float[160], impS = new float[160], impX = new float[160], impY = new float[160];
    private int impN, impIdx;

    // ------------------------------------------------------------------ particles

    private static final int P_MAX = 420;
    private static final int PT_STREAK = 0, PT_GLOW = 1, PT_SMOKE = 2, PT_MOTE = 3, PT_FOG = 4;
    private final float[] pX = new float[P_MAX], pY = new float[P_MAX], pVX = new float[P_MAX], pVY = new float[P_MAX],
            pLife = new float[P_MAX], pMax = new float[P_MAX], pSize = new float[P_MAX], pDrag = new float[P_MAX],
            pGrav = new float[P_MAX];
    private final int[] pColor = new int[P_MAX], pType = new int[P_MAX];
    private int pNext;

    // history scroll
    private float histScroll, histVel;

    // ------------------------------------------------------------------ input

    private float downX, downY, lastY;
    private long downTime;
    private boolean longFired, dragging, moved;
    private float rotaryAcc;
    private final Runnable longPress = () -> {
        longFired = true;
        onLongPress();
    };

    private final SensorManager sensors;
    private final Sensor accel;
    private long lastShake;

    // ------------------------------------------------------------------ drawing resources

    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint serif = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Paint archFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient redShader;
    private final Path archPath = new Path(), archInner = new Path();
    private final Matrix shaderM = new Matrix();
    private final Matrix homo = new Matrix();
    private final Path poly = new Path(), tmpPath = new Path();
    private final float[] src = {-1, -1, 1, -1, 1, 1, -1, 1};
    private final float[] dst = new float[8];
    private final float[] pv = new float[3], pv2 = new float[3];
    private final float[] projX = new float[24], projY = new float[24];
    private final RectF rect = new RectF();
    private final Quat tq1 = new Quat(), tq2 = new Quat(), tq3 = new Quat(), idleQ = new Quat(), ghostQ = new Quat();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private static final float CAM = 4f;
    private static final float[] LIGHT = norm(-0.38f, 0.58f, 0.72f);
    private static final float[] HALF = norm(-0.38f, 0.58f, 1.72f);

    // palette
    // accent = delerium magenta; frame = tarnished iron; text = bone and ash
    private static final int GOLD = 0xFFE04ACB, GOLD_HI = 0xFFFFA8F2, GOLD_DIM = 0xFF3E4250,
            IVORY = 0xFFEAE4D6, MUTED = 0xFF8E8A9C, RED = 0xFFFF3B5C, EMBER = 0xFFFF4FA0,
            IRON = 0xFF9AA2B0, IRON_HI = 0xFFD6DCE6, CYAN = 0xFF56F0DC, CYAN_HI = 0xFFB8FFF6, VIOLET = 0xFF8A4BFF;
    private static final int[] HAZE = {0xFFFF6AD5, 0xFFB46BFF, 0xFF56F0DC, 0xFFFFC46B, 0xFF7FA8FF};
    /** Delerium smoke: glowing violets and magentas. */
    private static final int[] FOG = {0xFFB23CC8, 0xFF7A3CFF, 0xFFE04ACB, 0xFF9A4BE8};
    private static final int[] FOG_PURE = {0xFF56F0DC, 0xFF8FFFF0, 0xFF5AB8FF};

    // die styles: 0 delerium crystal (iridescent, base unused), 1 obsidian, 2 pure delerium (crit)
    private static final int[][] BASE = {{150, 60, 170}, {30, 26, 38}, {178, 252, 244}};
    private static final int[][] EDGE = {{255, 196, 248}, {126, 132, 146}, {240, 255, 255}};
    private static final int[][] NUM = {{246, 240, 228}, {255, 130, 236}, {18, 46, 64}};
    private static final int[] FUMBLE_NUM = {255, 52, 80};
    private final float[] hsv = new float[3];

    DiceView(Context ctx, Roller roller, Sfx sfx) {
        super(ctx);
        this.roller = roller;
        this.sfx = sfx;
        sfx.soundOn = roller.sound;
        setFocusable(true);
        setFocusableInTouchMode(true);

        String[] tens = {"00", "10", "20", "30", "40", "50", "60", "70", "80", "90"};
        String[] units = {"0", "1", "2", "3", "4", "5", "6.", "7", "8", "9."};
        polys[Roller.KIND_D4] = Poly.d4();
        polys[Roller.KIND_D6] = Poly.d6();
        polys[Roller.KIND_D8] = Poly.d8();
        polys[Roller.KIND_D10] = Poly.d10(null);
        polys[Roller.KIND_D12] = Poly.d12();
        polys[Roller.KIND_D20] = Poly.d20();
        polys[Roller.KIND_TENS] = Poly.d10(tens);
        polys[Roller.KIND_UNITS] = Poly.d10(units);

        Typeface serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        Typeface ui = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(serifBold);
        tp.setTextSize(100);
        Matrix m = new Matrix();
        for (int k = 0; k < polys.length; k++) {
            Poly p = polys[k];
            labelPaths[k] = new Path[p.faces.length];
            for (int i = 0; i < p.faces.length; i++) {
                String s = p.faces[i].label;
                Path path = new Path();
                tp.getTextPath(s, 0, s.length(), 0, 0, path);
                path.computeBounds(rect, true);
                float sc = Math.min(1.55f / Math.max(1, rect.width()), 1.2f / Math.max(1, rect.height()));
                m.setTranslate(-rect.centerX(), -rect.centerY());
                m.postScale(sc, sc);
                path.transform(m);
                labelPaths[k][i] = path;
            }
        }

        face.setStyle(Paint.Style.FILL);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeJoin(Paint.Join.ROUND);
        label.setStyle(Paint.Style.FILL);
        text.setTypeface(ui);
        text.setTextAlign(Paint.Align.CENTER);
        serif.setTypeface(serifBold);
        serif.setTextAlign(Paint.Align.CENTER);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);

        for (int i = 0; i < anims.length; i++) anims[i] = new Anim();

        sensors = ctx.getSystemService(SensorManager.class);
        accel = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    // ------------------------------------------------------------------ lifecycle

    void resume() {
        running = true;
        lastNs = 0;
        if (accel != null && roller.shake) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
        requestFocus();
        invalidate();
    }

    void pause() {
        running = false;
        if (sensors != null) sensors.unregisterListener(this);
        roller.save();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        S = Math.min(w, h);
        cx = w / 2f;
        cy = h / 2f;
        bg.setShader(new RadialGradient(cx, cy * 0.9f, S * 0.66f,
                new int[]{0xFF15161F, 0xFF0C0C12, 0xFF040406}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        redShader = new RadialGradient(cx, cy, S * 0.55f, new int[]{0x00000000, 0x00000000, 0xFF8A0A3A},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

        buildArch(archPath, 0);
        buildArch(archInner, S * 0.017f);
        archFill.setShader(new LinearGradient(0, S * ARCH_T, 0, S * ARCH_B,
                new int[]{0xFF241E30, 0xFF16151E, 0xFF0D0D13}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Gothic lancet (drop) arch: two arcs meeting in a point, straight sides, flat sill. */
    private void buildArch(Path p, float inset) {
        float L = S * ARCH_L, Rr = S * ARCH_R, w = Rr - L, r0 = 0.75f * w;
        float ys = S * ARCH_T + (float) Math.sqrt(r0 * r0 - (r0 - w / 2) * (r0 - w / 2));
        float rr = r0 - inset, b = S * ARCH_B - inset, l = L + inset, r = Rr - inset, corner = S * 0.03f;
        float lcx = L + r0, rcx = Rr - r0;
        float aEnd = 360f - (float) Math.toDegrees(Math.acos((cx - lcx) / rr));
        float aStart = 360f - (float) Math.toDegrees(Math.acos((cx - rcx) / rr));
        p.reset();
        p.moveTo(l, b - corner);
        p.lineTo(l, ys);
        rect.set(lcx - rr, ys - rr, lcx + rr, ys + rr);
        p.arcTo(rect, 180, aEnd - 180, false);
        rect.set(rcx - rr, ys - rr, rcx + rr, ys + rr);
        p.arcTo(rect, aStart, 360 - aStart, false);
        p.lineTo(r, b - corner);
        rect.set(r - 2 * corner, b - 2 * corner, r, b);
        p.arcTo(rect, 0, 90, false);
        p.lineTo(l + corner, b);
        rect.set(l, b - 2 * corner, l + 2 * corner, b);
        p.arcTo(rect, 90, 90, false);
        p.close();
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void onDraw(Canvas c) {
        long ns = clockOverrideNs >= 0 ? clockOverrideNs : System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        now += dt;
        update(dt);

        c.drawRect(0, 0, getWidth(), getHeight(), bg);
        c.save();
        float st = now - shakeAt;
        if (st >= 0 && st < 0.5f) {
            float a = S * 0.02f * (1 - st / 0.5f);
            c.translate((float) Math.sin(st * 70) * a, (float) Math.cos(st * 53) * a * 0.6f);
        }

        drawFrame(c);
        drawArch(c);
        drawGroundFog(c);
        drawParticles(c, dt, true);
        drawHeader(c);
        if (idleA > 0.01f) drawIdle(c);
        if (state == ROLLING && fromIdle) drawVanish(c);
        if (rollA > 0.01f && animCount > 0) drawRoll(c);
        drawParticles(c, dt, false);
        if (state == RESULT) drawWisp(c);
        if (rollA > 0.01f && state == RESULT) drawResultText(c);
        if (chipsA > 0.01f) drawChips(c);
        c.restore();

        if (flashA > 0.01f) {
            fill.setShader(null);
            fill.setColor(Color.argb((int) (flashA * 150), 200, 255, 248));
            c.drawCircle(cx, cy, S * 0.75f, fill);
        }
        if (redA > 0.01f) {
            fill.setShader(redShader);
            fill.setAlpha((int) (redA * 210));
            c.drawRect(0, 0, getWidth(), getHeight(), fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        if (histX < 0.995f) drawHistory(c);
        if (settingsA > 0.01f) drawSettings(c);

        if (running) postInvalidateOnAnimation();
    }

    private void update(float dt) {
        float k = 1 - (float) Math.exp(-dt * 11);
        chipsA += ((state == IDLE ? 1 : 0) - chipsA) * k;
        idleA += ((state == IDLE ? 1 : 0) - idleA) * k;
        rollA += ((state == IDLE ? 0 : 1) - rollA) * k;
        histX += ((historyOpen ? 0 : 1) - histX) * (1 - (float) Math.exp(-dt * 13));
        settingsA += ((settingsOpen ? 1 : 0) - settingsA) * k;
        flashA *= (float) Math.exp(-dt * 5);
        if (now >= shakeAt) redA *= (float) Math.exp(-dt * 2.2);

        emitHaze(dt);
        if (state == IDLE) emitIdleSmoke(dt);

        if (historyOpen && !dragging) {
            histScroll += histVel * dt;
            histVel *= (float) Math.exp(-dt * 4);
            clampHistory();
        }

        if (state == ROLLING) {
            float t = now - rollT0;
            if (!flightPlayed && t >= F0) {
                flightPlayed = true;
                sfx.flightStart();
            }
            while (impIdx < impN && impT[impIdx] <= t) {
                sfx.impact(impS[impIdx]);
                sparkBurst(impX[impIdx], impY[impIdx], (int) (5 + 9 * impS[impIdx]), S * 0.5f * impS[impIdx] + S * 0.1f, GOLD_HI);
                impIdx++;
            }
            stepAnims(t);
            emitTrails();
            if (t >= landAt) land();
        } else if (state == RESULT) {
            float rt = now - revealT0;
            if (!wispPlayed && rt >= WISP_T) {
                wispPlayed = true;
                sfx.wisp();
            }
            if (rt >= WISP_T && rt < DING_T) emitWispTrail();
            if (!dingPlayed && rt >= DING_T) {
                dingPlayed = true;
                sfx.ding();
                wispTarget(pv);
                sparkBurst(pv[0], pv[1], 14, S * 0.35f, GOLD_HI);
            }
            if (!verdictPlayed && rt >= VERDICT_T) {
                verdictPlayed = true;
                playVerdict();
            }
            float vt = rt - VERDICT_T;
            if (result.fumble && vt >= 0 && vt < 0.32f) emitFlameStreak(vt);
            if (result.fumble && vt >= 0.25f) emitSmoke(dt, vt);
            if (result.crit && vt >= 0) emitGoldMotes(dt);
        }
    }

    // ------------------------------------------------------------------ backdrop

    private void drawFrame(Canvas c) {
        float spin = now * 0.04f;
        line.setStrokeWidth(S * 0.0035f);
        line.setColor(withAlpha(IRON, 90));
        c.drawCircle(cx, cy, S * 0.488f, line);
        c.save();
        c.rotate((float) Math.toDegrees(spin), cx, cy);
        for (int i = 0; i < 48; i++) {
            float ang = (float) (i * Math.PI * 2 / 48);
            float r0 = S * (i % 4 == 0 ? 0.468f : 0.474f), r1 = S * 0.486f;
            float sn = (float) Math.sin(ang), cs = (float) Math.cos(ang);
            line.setColor(i % 4 == 0 ? withAlpha(IRON, 150) : withAlpha(GOLD_DIM, 160));
            c.drawLine(cx + cs * r0, cy + sn * r0, cx + cs * r1, cy + sn * r1, line);
        }
        c.restore();
    }

    private void drawArch(Canvas c) {
        c.drawPath(archPath, archFill);
        // the Haze pooling around the die, and a faint violet glow under the peak
        drawGlow(c, cx, S * DIE_Y, S * 0.3f, 0xFF5A2D7A, 0.6f);
        drawGlow(c, cx, S * 0.2f, S * 0.18f, 0xFF7A2D6E, 0.35f);
        line.setStrokeWidth(S * 0.009f);
        line.setColor(0xFF1E2028);
        c.drawPath(archPath, line);
        line.setStrokeWidth(S * 0.005f);
        line.setColor(IRON);
        c.drawPath(archPath, line);
        line.setStrokeWidth(S * 0.0022f);
        line.setColor(withAlpha(IRON, 110));
        c.drawPath(archInner, line);
        // iron rivets down the jambs
        fill.setShader(null);
        for (int i = 0; i < 4; i++) {
            float y = S * (0.6f + i * 0.06f);
            fill.setColor(IRON_HI);
            c.drawCircle(S * ARCH_L, y, S * 0.0055f, fill);
            c.drawCircle(S * ARCH_R, y, S * 0.0055f, fill);
        }
        // delerium crystal cluster crowning the peak
        float py = S * ARCH_T;
        float pulse = 0.75f + 0.25f * (float) Math.sin(now * 2.2f);
        drawGlow(c, cx, py, S * 0.07f, GOLD, 0.55f * pulse);
        crystal(c, cx, py + S * 0.004f, S * 0.012f, S * 0.05f, 0);
        crystal(c, cx - S * 0.02f, py + S * 0.012f, S * 0.008f, S * 0.03f, -0.45f);
        crystal(c, cx + S * 0.02f, py + S * 0.012f, S * 0.008f, S * 0.03f, 0.45f);
        // divider under the DC with a crystal shard in the middle
        float y = S * DIVIDER_Y;
        line.setStrokeWidth(S * 0.0028f);
        line.setColor(withAlpha(IRON, 150));
        c.drawLine(S * 0.26f, y, cx - S * 0.03f, y, line);
        c.drawLine(cx + S * 0.03f, y, S * 0.74f, y, line);
        drawGlow(c, cx, y, S * 0.03f, GOLD, 0.5f * pulse);
        fill.setColor(GOLD_HI);
        diamond(c, cx, y, S * 0.013f);
    }

    /** A glowing delerium shard: an elongated, faceted diamond leaning by tilt (radians). */
    private void crystal(Canvas c, float x, float baseY, float hw, float h, float tilt) {
        c.save();
        c.rotate((float) Math.toDegrees(tilt), x, baseY);
        poly.reset();
        poly.moveTo(x, baseY - h);
        poly.lineTo(x + hw, baseY - h * 0.35f);
        poly.lineTo(x, baseY);
        poly.lineTo(x - hw, baseY - h * 0.35f);
        poly.close();
        fill.setShader(null);
        fill.setColor(0xFFB13BC4);
        c.drawPath(poly, fill);
        poly.reset();
        poly.moveTo(x, baseY - h);
        poly.lineTo(x + hw, baseY - h * 0.35f);
        poly.lineTo(x, baseY);
        poly.close();
        fill.setColor(0xFFFF8BEA);
        c.drawPath(poly, fill);
        c.restore();
    }

    /** Delerium smoke pooled on the sill of the window, slowly churning (faster while rolling). */
    private void drawGroundFog(Canvas c) {
        c.save();
        c.clipPath(archPath);
        float sp = state == ROLLING ? 2.5f : 1f;
        for (int i = 0; i < 7; i++) {
            float ph = i * 1.7f;
            float t = now * sp;
            float x = cx + S * 0.27f * (float) Math.sin(t * 0.11f * (1 + i * 0.13f) + ph);
            float y = S * (0.62f + 0.06f * (float) Math.sin(t * 0.17f + ph * 1.3f));
            float r = S * (0.14f + 0.04f * (float) Math.sin(t * 0.23f + ph));
            float a = 0.2f + 0.08f * (float) Math.sin(t * 0.4f + ph * 2);
            drawGlow(c, x, y, r, FOG[i % FOG.length], a);
        }
        c.restore();
    }

    private void diamond(Canvas c, float x, float y, float d) {
        poly.reset();
        poly.moveTo(x, y - d);
        poly.lineTo(x + d * 0.72f, y);
        poly.lineTo(x, y + d);
        poly.lineTo(x - d * 0.72f, y);
        poly.close();
        c.drawPath(poly, fill);
    }

    /** "КЛАСС СЛОЖНОСТИ" and the DC (or the roll expression when there is no DC). */
    private void drawHeader(Canvas c) {
        boolean on = active == Roller.P_DC && state == IDLE;
        boolean idle = state == IDLE || result == null;
        boolean hasDc = idle ? roller.dc > 0 : result.dc > 0;
        text.setTextSize(S * 0.027f);
        text.setLetterSpacing(0.12f);
        text.setColor(on ? GOLD : MUTED);
        c.drawText(hasDc ? "КЛАСС СЛОЖНОСТИ" : "БРОСОК", cx, S * HEAD_CAP_Y, text);
        text.setLetterSpacing(0);

        String value;
        if (idle) value = roller.dc > 0 ? String.valueOf(roller.dc) : roller.expression();
        else value = result.dc > 0 ? String.valueOf(result.dc) : result.expr;
        float size = S * (hasDc ? 0.088f : 0.064f);
        int col = on ? GOLD_HI : IVORY;
        float scale = 1;
        if (!idle) {
            float rt = now - revealT0;
            if (state == RESULT && hasDc && rt >= DING_T) scale = 1 + 0.32f * (float) Math.exp(-(rt - DING_T) * 7);
            if (state == RESULT && hasDc && rt >= VERDICT_T) {
                float m = Math.min(1, (rt - VERDICT_T) / 0.25f);
                col = mixColor(IVORY, roller.passed(result) ? CYAN_HI : RED, m);
            }
        }
        serif.setTextSize(fitSize(value, size * scale, S * 0.42f, serif));
        serif.setColor(withAlpha(0xFF000000, 140));
        c.drawText(value, cx + S * 0.003f, S * HEAD_NUM_Y + S * 0.005f, serif);
        serif.setColor(col);
        c.drawText(value, cx, S * HEAD_NUM_Y, serif);
        if (on) {
            fill.setShader(null);
            fill.setColor(GOLD);
            float w = serif.measureText(value) / 2 + S * 0.04f;
            triangleSide(c, cx - w, S * HEAD_NUM_Y - S * 0.025f, S * 0.016f, true);
            triangleSide(c, cx + w, S * HEAD_NUM_Y - S * 0.025f, S * 0.016f, false);
        }
    }

    // ------------------------------------------------------------------ idle die

    private int idleKind() {
        int s = roller.sides();
        return s == 100 ? Roller.KIND_TENS : Roller.kindFor(s);
    }

    private void computeIdleQ(Quat out) {
        Poly p = polys[idleKind()];
        Poly.Face f = p.faceFor(idleKind() == Roller.KIND_TENS ? 0 : p.sides);
        // gentle sway that keeps the top face readable, like the die waiting in the BG3 panel
        tq1.setAxisAngle(0, 1, 0, Math.sin(now * 0.9f) * 0.42f);
        tq2.setAxisAngle(1, 0, 0, 0.3f + Math.sin(now * 0.63f) * 0.12f);
        out.mul(tq1, tq2).mul(out, f.show);
    }

    private float idleSize() { return S * DIE_SIZE; }

    private float idleY() { return S * DIE_Y + (float) Math.sin(now * 1.7f) * S * 0.006f; }

    private void drawIdle(Canvas c) {
        float pop = Math.min(1, (now - idleSwapAt) / 0.38f);
        float size = idleSize() * (0.55f + 0.45f * easeOutBack(pop)) * (0.75f + 0.25f * idleA);
        float y = idleY();
        computeIdleQ(idleQ);
        drawGlow(c, cx, y, size * 1.8f, roller.skin == 0 ? 0xFFD84AE0 : 0xFF8A3A9A, 0.32f * idleA);
        drawDie(c, polys[idleKind()], idleKind(), idleQ, cx, y, size, idleA, roller.skin, 0, -1);
        text.setTextSize(S * 0.03f);
        text.setColor(withAlpha(MUTED, (int) (idleA * 230)));
        c.drawText("нажми, чтобы бросить", cx, S * 0.705f, text);
    }

    /** The resting die pops up and vanishes as it is thrown. */
    private void drawVanish(Canvas c) {
        float t = now - rollT0;
        if (t >= VANISH) return;
        float k = t / VANISH;
        float sc = k < 0.3f ? 1 + 0.12f * (k / 0.3f) : 1.12f * (1 - (k - 0.3f) / 0.7f);
        float y = S * DIE_Y - S * 0.06f * k;
        drawDie(c, polys[idleKind()], idleKind(), vanishQ, cx, y, idleSize() * Math.max(0.01f, sc), 1 - k * k,
                roller.skin, 0, -1);
    }

    // ------------------------------------------------------------------ chips

    private float sideR() { return S * 0.066f; }

    private float leftX() { return S * 0.105f; }

    private float rightX() { return S * 0.895f; }

    private float pillY() { return S * 0.885f; }

    private boolean modeShown() { return roller.modeAvailable(); }

    private float dieChipX() { return modeShown() ? S * 0.375f : cx; }

    private float modeChipX() { return S * 0.625f; }

    private void drawChips(Canvas c) {
        int a = (int) (chipsA * 255);
        boolean countOn = roller.sides() != 100 && roller.effectiveMode() == Roller.MODE_NORMAL;
        drawRoundChip(c, leftX(), S * DIE_Y, sideR(), active == Roller.P_COUNT, countOn, "КОЛ-ВО",
                roller.effectiveCount() + "×", a);
        String mod = roller.mod == 0 ? "0" : (roller.mod > 0 ? "+" : "−") + Math.abs(roller.mod);
        drawRoundChip(c, rightX(), S * DIE_Y, sideR(), active == Roller.P_MOD, true, "МОД", mod, a);
        String die = roller.sides() == 100 ? "d100" : "d" + roller.sides();
        drawPill(c, dieChipX(), pillY(), S * 0.23f, S * 0.075f, active == Roller.P_DIE, die, S * 0.045f, a);
        if (active == Roller.P_DIE) {
            fill.setColor(withAlpha(GOLD, a));
            triangleSide(c, dieChipX() - S * 0.085f, pillY(), S * 0.014f, true);
            triangleSide(c, dieChipX() + S * 0.085f, pillY(), S * 0.014f, false);
        }
        if (modeShown()) {
            drawPill(c, modeChipX(), pillY(), S * 0.23f, S * 0.075f, active == Roller.P_MODE,
                    roller.modeLabel(), S * 0.032f, a);
        }
    }

    private void drawPill(Canvas c, float x, float y, float w, float h, boolean on, String value, float size, int a) {
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        fill.setShader(null);
        fill.setColor(withAlpha(on ? 0xFF2B1D33 : 0xFF14141B, (int) (a * 0.95f)));
        c.drawRoundRect(rect, h / 2, h / 2, fill);
        line.setStrokeWidth(S * (on ? 0.006f : 0.0035f));
        line.setColor(withAlpha(on ? GOLD : GOLD_DIM, a));
        c.drawRoundRect(rect, h / 2, h / 2, line);
        text.setTextSize(size);
        text.setColor(withAlpha(on ? GOLD_HI : IVORY, a));
        c.drawText(value, x, y + size * 0.36f, text);
    }

    private void drawRoundChip(Canvas c, float x, float y, float r, boolean on, boolean enabled,
                               String caption, String value, int a) {
        fill.setShader(null);
        fill.setColor(withAlpha(on ? 0xFF2B1D33 : 0xFF14141B, (int) (a * 0.95f)));
        c.drawCircle(x, y, r, fill);
        line.setStrokeWidth(S * (on ? 0.006f : 0.0035f));
        line.setColor(withAlpha(on ? GOLD : GOLD_DIM, a));
        c.drawCircle(x, y, r, line);
        text.setTextSize(S * 0.024f);
        text.setColor(withAlpha(on ? GOLD : MUTED, a));
        c.drawText(caption, x, y - r * 0.36f, text);
        text.setTextSize(S * 0.052f);
        text.setColor(withAlpha(!enabled ? MUTED : on ? GOLD_HI : IVORY, enabled ? a : a / 2));
        c.drawText(value, x, y + r * 0.5f, text);
        if (on) {
            fill.setColor(withAlpha(GOLD, a));
            triangle(c, x, y - r - S * 0.02f, S * 0.015f, true);
            triangle(c, x, y + r + S * 0.02f, S * 0.015f, false);
        }
    }

    private void triangle(Canvas c, float x, float y, float h, boolean up) {
        float d = up ? -1 : 1;
        poly.reset();
        poly.moveTo(x, y + d * h * 0.6f);
        poly.lineTo(x + h * 0.7f, y - d * h * 0.4f);
        poly.lineTo(x - h * 0.7f, y - d * h * 0.4f);
        poly.close();
        c.drawPath(poly, fill);
    }

    private void triangleSide(Canvas c, float x, float y, float h, boolean left) {
        float d = left ? -1 : 1;
        poly.reset();
        poly.moveTo(x + d * h * 0.6f, y);
        poly.lineTo(x - d * h * 0.4f, y - h * 0.7f);
        poly.lineTo(x - d * h * 0.4f, y + h * 0.7f);
        poly.close();
        c.drawPath(poly, fill);
    }

    // ------------------------------------------------------------------ rolling

    private void startRoll() {
        if (state == ROLLING || historyOpen || settingsOpen) return;
        fromIdle = state == IDLE;
        if (fromIdle) computeIdleQ(vanishQ);

        result = roller.roll();
        animCount = result.dice.size();
        float[][] lay = layout(animCount);
        impN = 0;
        impIdx = 0;
        float maxDelay = 0;
        double baseAng = rng.nextDouble() * Math.PI * 2;
        for (int i = 0; i < animCount; i++) {
            Roller.DieRoll d = result.dice.get(i);
            Anim an = anims[i];
            an.kind = d.kind;
            an.poly = polys[d.kind];
            an.dropped = d.dropped;
            an.tx = cx + lay[i][0] * S;
            an.ty = S * DIE_Y + lay[i][1] * S;
            an.size = lay[i][2] * S;
            an.delay = i == 0 ? 0 : i * 0.05f + rng.nextFloat() * 0.06f;
            maxDelay = Math.max(maxDelay, an.delay);

            Poly.Face f = an.poly.faceFor(d.value);
            an.faceIdx = 0;
            for (int fi = 0; fi < an.poly.faces.length; fi++) if (an.poly.faces[fi] == f) an.faceIdx = fi;
            float twist = (rng.nextFloat() - 0.5f) * (animCount > 1 ? 0.45f : 0.14f);
            tq1.setAxisAngle(1, 0, 0, 0.27f);
            tq2.setAxisAngle(0, 1, 0, -0.2f);
            tq3.setAxisAngle(0, 0, 1, twist);
            an.qFinal.mul(tq1, tq2).mul(an.qFinal, tq3).mul(an.qFinal, f.show);
            an.qStart.random(rng);
            Quat.randomAxis(rng, an.ax1);
            Quat.randomAxis(rng, an.ax2);
            an.a1 = (float) (Math.PI * 2 * (2.6 + rng.nextFloat() * 1.4));
            an.a2 = (float) (Math.PI * (0.8 + rng.nextFloat() * 1.2));
            tq1.setAxisAngle(an.ax1[0], an.ax1[1], an.ax1[2], an.a1);
            tq2.setAxisAngle(an.ax2[0], an.ax2[1], an.ax2[2], an.a2);
            tq3.mul(an.qFinal, tq1).mul(tq3, tq2);
            an.delta.conj(tq3).mul(an.delta, an.qStart).normalize();

            simulate(an, baseAng + i * 2.4 + (rng.nextDouble() - 0.5) * 0.8, i < 3);
        }
        sortImpacts();
        landAt = F0 + FD + maxDelay;
        rollT0 = now;
        state = ROLLING;
        idleA = 0;
        rollA = 1;
        flightPlayed = wispPlayed = dingPlayed = verdictPlayed = false;
        for (int i = 0; i < P_MAX; i++) if (pType[i] != PT_MOTE) pLife[i] = 0;
        for (int i = 0; i < animCount; i++) { anims[i].x = anims[i].px[0]; anims[i].y = anims[i].py[0]; }
        stepAnims(0);
        sfx.throwStart();
    }

    /**
     * Pre-computes the flight: the die enters from off-screen, ricochets off the walls of an
     * invisible box inside the arch with friction, and is eased onto its resting spot.
     */
    private void simulate(Anim an, double ang, boolean sounds) {
        float bx0 = S * BOX_L, bx1 = S * BOX_R, by0 = S * BOX_T, by1 = S * BOX_B;
        float x = cx + (float) Math.cos(ang) * S * 0.56f, y = S * DIE_Y + (float) Math.sin(ang) * S * 0.56f;
        float ax = cx + (rng.nextFloat() - 0.5f) * S * 0.16f, ay = S * DIE_Y + (rng.nextFloat() - 0.5f) * S * 0.12f;
        float sp = S * (3.1f + rng.nextFloat() * 0.5f);
        float dx = ax - x, dy = ay - y, l = (float) Math.hypot(dx, dy);
        float vx = dx / l * sp, vy = dy / l * sp;
        float swirl = (rng.nextFloat() - 0.5f) * 2.2f;   // slight curve so the path is not a straight shot
        boolean inside = false;
        float dt = 1f / PATH_HZ, blendStart = FD - BLEND;
        float damp = (float) Math.exp(-1.3f * dt);
        for (int i = 0; i < PATH_N; i++) {
            float t = i * dt;
            if (i > 0) {
                float nvx = vx - vy * swirl * dt, nvy = vy + vx * swirl * dt;
                vx = nvx * damp;
                vy = nvy * damp;
                x += vx * dt;
                y += vy * dt;
            }
            float w = smooth((t - blendStart) / BLEND);
            if (!inside && x > bx0 && x < bx1 && y > by0 && y < by1) inside = true;
            if (inside) {
                boolean hit = false;
                float ix = x, iy = y;
                if (x < bx0) { x = bx0 + (bx0 - x); vx = -vx * 0.8f; hit = true; ix = bx0; }
                if (x > bx1) { x = bx1 - (x - bx1); vx = -vx * 0.8f; hit = true; ix = bx1; }
                if (y < by0) { y = by0 + (by0 - y); vy = -vy * 0.8f; hit = true; iy = by0; }
                if (y > by1) { y = by1 - (y - by1); vy = -vy * 0.8f; hit = true; iy = by1; }
                if (hit) {
                    vx += (rng.nextFloat() - 0.5f) * S * 0.25f;
                    float speed = (float) Math.hypot(vx, vy);
                    if (sounds && w < 0.5f && impN < impT.length && speed > S * 0.2f) {
                        impT[impN] = F0 + an.delay + t;
                        impS[impN] = Math.min(1f, speed / (S * 2.6f));
                        impX[impN] = lerp(ix, an.tx, w);
                        impY[impN] = lerp(iy, an.ty, w);
                        impN++;
                    }
                }
            }
            an.px[i] = lerp(x, an.tx, w);
            an.py[i] = lerp(y, an.ty, w);
        }
    }

    private void sortImpacts() {
        for (int i = 1; i < impN; i++) {
            float t = impT[i], s = impS[i], x = impX[i], y = impY[i];
            int j = i - 1;
            while (j >= 0 && impT[j] > t) {
                impT[j + 1] = impT[j]; impS[j + 1] = impS[j]; impX[j + 1] = impX[j]; impY[j + 1] = impY[j];
                j--;
            }
            impT[j + 1] = t; impS[j + 1] = s; impX[j + 1] = x; impY[j + 1] = y;
        }
    }

    /** Final positions relative to the die spot, and circumradius, all as fractions of S. */
    private static float[][] layout(int n) {
        switch (n) {
            case 1: return new float[][]{{0, 0, DIE_SIZE}};
            case 2: return new float[][]{{-0.135f, 0, 0.112f}, {0.135f, 0, 0.112f}};
            case 3: return new float[][]{{-0.14f, -0.06f, 0.098f}, {0.14f, -0.06f, 0.098f}, {0, 0.1f, 0.098f}};
            case 4: return new float[][]{{-0.115f, -0.095f, 0.088f}, {0.115f, -0.095f, 0.088f},
                    {-0.115f, 0.095f, 0.088f}, {0.115f, 0.095f, 0.088f}};
            default: {
                int[] rows;
                switch (n) {
                    case 5: rows = new int[]{3, 2}; break;
                    case 6: rows = new int[]{3, 3}; break;
                    case 7: rows = new int[]{2, 3, 2}; break;
                    case 8: rows = new int[]{3, 2, 3}; break;
                    case 9: rows = new int[]{3, 3, 3}; break;
                    default: rows = new int[]{3, 4, 3}; break;
                }
                float size = n <= 6 ? 0.075f : 0.062f;
                float dy = n <= 6 ? 0.17f : 0.13f, dx = n <= 6 ? 0.155f : 0.13f;
                float[][] out = new float[n][];
                int k = 0, ri = 0;
                for (int r : rows) {
                    float y = (ri - (rows.length - 1) / 2f) * dy;
                    for (int j = 0; j < r && k < n; j++) {
                        out[k++] = new float[]{(j - (r - 1) / 2f) * dx, y, size};
                    }
                    ri++;
                }
                return out;
            }
        }
    }

    /** Orientation at flight time tf (seconds since this die started flying). */
    private void orient(Anim an, float tf, Quat out) {
        float p = clamp01(tf / FD);
        float e1 = 1 - (float) Math.pow(1 - p, 3);
        float e2 = 1 - (float) Math.pow(1 - p, 2);
        tq1.setAxisAngle(an.ax1[0], an.ax1[1], an.ax1[2], an.a1 * (1 - e1));
        tq2.setAxisAngle(an.ax2[0], an.ax2[1], an.ax2[2], an.a2 * (1 - e2));
        tq3.pow(an.delta, 1 - e1);
        out.mul(an.qFinal, tq1).mul(out, tq2).mul(out, tq3).normalize();
    }

    private float pathAt(float[] arr, float tf) {
        float fi = clamp(tf, 0, FD) * PATH_HZ;
        int i = Math.min(PATH_N - 2, (int) fi);
        float f = fi - i;
        return arr[i] + (arr[i + 1] - arr[i]) * f;
    }

    private void stepAnims(float t) {
        for (int i = 0; i < animCount; i++) {
            Anim an = anims[i];
            float tf = t - F0 - an.delay;
            float ox = an.x, oy = an.y;
            an.x = pathAt(an.px, tf);
            an.y = pathAt(an.py, tf);
            float landing = smooth((tf - (FD - BLEND)) / BLEND);
            an.sz = an.size * (1 + 0.16f * (1 - landing));
            an.speed = (float) Math.hypot(an.x - ox, an.y - oy) * 60;
            orient(an, tf, an.q);
        }
    }

    private void land() {
        state = RESULT;
        revealT0 = now;
        stepAnims(landAt + 1);
        sfx.landed();
        for (int i = 0; i < animCount; i++) {
            Anim an = anims[i];
            sparkBurst(an.x, an.y, animCount > 3 ? 8 : 22, S * 0.55f, GOLD_HI);
            fogRing(an.x, an.y + an.size * 0.3f, an.size, animCount > 3 ? 5 : 12, FOG);
        }
    }

    /** Skip ahead (tap during the animation). */
    private void fastForward() {
        if (state == ROLLING) {
            state = RESULT;
            stepAnims(landAt + 1);
            revealT0 = now - DING_T;
            wispPlayed = dingPlayed = true;
            sfx.stopFlight();
            for (int i = 0; i < P_MAX; i++) if (pType[i] != PT_MOTE) pLife[i] = 0;
        } else if (state == RESULT && now - revealT0 < VERDICT_T) {
            revealT0 = now - VERDICT_T;
            wispPlayed = dingPlayed = true;
        }
    }

    private Anim mainAnim() {
        for (int i = 0; i < animCount; i++) if (!anims[i].dropped) return anims[i];
        return anims[0];
    }

    private void playVerdict() {
        Anim main = mainAnim();
        if (result.crit) {
            sfx.critSuccess();
            flashA = 1;
            sparkBurst(main.x, main.y, 60, S * 1.1f, CYAN_HI);
            fogRing(main.x, main.y, main.size * 1.4f, 16, FOG_PURE);
        } else if (result.fumble) {
            sfx.critFailure();
            shakeAt = now + 0.3f;
            redA = 1;
            fogRing(main.x, main.y, main.size * 1.2f, 10, new int[]{0xFF8A1A5A, 0xFF5A1A7A});
        } else if (result.dc > 0) {
            boolean ok = roller.passed(result);
            sfx.verdict(ok);
            sparkBurst(cx, S * HEAD_NUM_Y - S * 0.03f, 16, S * 0.4f, ok ? CYAN : RED);
        }
    }

    private void drawRoll(Canvas c) {
        float rt = state == RESULT ? now - revealT0 : -1;
        float vt = state == RESULT ? rt - VERDICT_T : -1;
        float styleMix = vt >= 0 ? Math.min(1, vt / 0.35f) : 0;
        float t = now - rollT0;
        int skin = roller.skin;
        for (int i = 0; i < animCount; i++) {
            Anim an = anims[i];
            float tf = t - F0 - an.delay;
            if (state == ROLLING && tf < 0) continue;
            float dropFade = an.dropped && rt >= 0 ? Math.min(1, rt / 0.5f) : 0;
            float alpha = rollA * (1 - 0.62f * dropFade);
            float sz = an.sz * (1 - 0.15f * dropFade);
            boolean main = !an.dropped;
            int style = main && result.crit ? 2 : skin;
            float mix = main && result.crit ? styleMix : 0;
            boolean fumbleFace = main && result.fumble && vt >= 0.18f;

            int glowC = skin == 0 ? 0xFFD84AE0 : 0xFF8A3A9A;
            float glowA = 0.25f;
            if (main && result.crit && vt >= 0) { glowC = 0xFF7FFFF0; glowA = 0.45f + 0.15f * (float) Math.sin(now * 4); }
            if (main && result.fumble && vt >= 0) { glowC = 0xFF7A0A3C; glowA = 0.6f * Math.min(1, vt / 0.3f); }
            drawGlow(c, an.x, an.y, sz * 1.9f, glowC, glowA * alpha);

            // motion-blur ghosts while flying fast
            if (state == ROLLING && animCount <= 3 && an.speed > S * 0.6f) {
                float g = Math.min(1, an.speed / (S * 2.5f));
                for (int k = 2; k >= 1; k--) {
                    float gt = tf - 0.022f * k;
                    orient(an, gt, ghostQ);
                    drawDie(c, an.poly, an.kind, ghostQ, pathAt(an.px, gt), pathAt(an.py, gt), sz,
                            alpha * g * (k == 1 ? 0.3f : 0.14f), skin, 0, -1);
                }
            }
            drawDie(c, an.poly, an.kind, an.q, an.x, an.y, sz, alpha, style, mix, fumbleFace ? an.faceIdx : -1);
        }
    }

    private void drawResultText(Canvas c) {
        float rt = now - revealT0;
        if (rt < DING_T) return;
        float a = rollA;
        float st = rt - DING_T;
        if (!result.breakdown.isEmpty()) {
            float ba = Math.min(1, st / 0.3f);
            text.setTextSize(fitSize(result.breakdown, S * 0.036f, S * 0.5f, text));
            text.setColor(withAlpha(MUTED, (int) (a * ba * 255)));
            c.drawText(result.breakdown, cx, S * BREAK_Y, text);
        }
        float pop = easeOutBack(Math.min(1, st / 0.4f));
        serif.setTextSize(S * 0.092f * (0.4f + 0.6f * pop));
        String total = String.valueOf(result.total);
        int col = IVORY;
        float vt = rt - VERDICT_T;
        if (vt >= 0) {
            float m = Math.min(1, vt / 0.25f);
            if (result.crit) col = mixColor(IVORY, CYAN_HI, m);
            else if (result.fumble) col = mixColor(IVORY, RED, m);
        }
        serif.setColor(withAlpha(0xFF000000, (int) (a * 150)));
        c.drawText(total, cx + S * 0.004f, S * TOTAL_Y + S * 0.006f, serif);
        serif.setColor(withAlpha(col, (int) (a * 255 * Math.min(1, st / 0.12f))));
        c.drawText(total, cx, S * TOTAL_Y, serif);

        if (vt < 0) return;
        String v = null;
        int vc = GOLD;
        if (result.crit) { v = "КРИТИЧЕСКИЙ УСПЕХ"; vc = CYAN_HI; }
        else if (result.fumble) { v = "КРИТИЧЕСКИЙ ПРОВАЛ"; vc = RED; }
        else if (result.dc > 0) {
            boolean ok = roller.passed(result);
            v = ok ? "УСПЕХ" : "ПРОВАЛ";
            vc = ok ? CYAN : RED;
        } else if (result.mode != Roller.MODE_NORMAL) {
            v = result.mode == Roller.MODE_ADV ? "ПРЕИМУЩЕСТВО" : "ПОМЕХА";
            vc = MUTED;
        }
        if (v == null) return;
        float va = Math.min(1, vt / 0.3f);
        float grow = 1.25f - 0.25f * easeOut(va);
        serif.setTextSize(fitSize(v, S * 0.043f * grow, S * 0.52f, serif));
        serif.setColor(withAlpha(0xFF000000, (int) (a * va * 170)));
        c.drawText(v, cx + S * 0.003f, S * VERDICT_Y + S * 0.004f, serif);
        serif.setColor(withAlpha(vc, (int) (a * va * 255)));
        c.drawText(v, cx, S * VERDICT_Y, serif);
    }

    // ------------------------------------------------------------------ the golden spark

    private void wispTarget(float[] out) {
        out[0] = cx;
        out[1] = result != null && result.dc > 0 ? S * HEAD_NUM_Y - S * 0.03f : S * TOTAL_Y - S * 0.03f;
    }

    private void wispPos(float k, float[] out) {
        Anim m = mainAnim();
        wispTarget(out);
        float x0 = m.x, y0 = m.y, x2 = out[0], y2 = out[1];
        float side = (result.total % 2 == 0) ? 1 : -1;
        float x1 = (x0 + x2) / 2 + side * S * 0.2f, y1 = (y0 + y2) / 2;
        float u = 1 - k;
        out[0] = u * u * x0 + 2 * u * k * x1 + k * k * x2;
        out[1] = u * u * y0 + 2 * u * k * y1 + k * k * y2;
    }

    private void emitWispTrail() {
        float k = easeInOut(clamp01((now - revealT0 - WISP_T) / WISP_D));
        wispPos(k, pv);
        for (int i = 0; i < 3; i++) {
            spawn(pv[0], pv[1], (rng.nextFloat() - 0.5f) * S * 0.12f, (rng.nextFloat() - 0.5f) * S * 0.12f,
                    0.35f + rng.nextFloat() * 0.25f, S * (0.006f + rng.nextFloat() * 0.006f), GOLD_HI, PT_GLOW, 2f, 0);
        }
    }

    private void drawWisp(Canvas c) {
        float rt = now - revealT0;
        if (rt < WISP_T || rt > DING_T) return;
        float k = easeInOut(clamp01((rt - WISP_T) / WISP_D));
        wispPos(k, pv);
        drawGlow(c, pv[0], pv[1], S * 0.06f, 0xFFFF7AE6, 0.9f);
        fill.setShader(null);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(pv[0], pv[1], S * 0.008f, fill);
    }

    // ------------------------------------------------------------------ particles

    private void spawn(float x, float y, float vx, float vy, float life, float size, int color, int type,
                       float drag, float grav) {
        int i = pNext;
        pNext = (pNext + 1) % P_MAX;
        pX[i] = x; pY[i] = y; pVX[i] = vx; pVY[i] = vy;
        pLife[i] = pMax[i] = life;
        pSize[i] = size; pColor[i] = color; pType[i] = type; pDrag[i] = drag; pGrav[i] = grav;
    }

    private void sparkBurst(float x, float y, int n, float speed, int color) {
        for (int i = 0; i < n; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float sp = speed * (0.3f + rng.nextFloat() * 0.7f);
            spawn(x, y, (float) Math.cos(a) * sp, (float) Math.sin(a) * sp - S * 0.08f,
                    0.35f + rng.nextFloat() * 0.6f, S * 0.005f, color, PT_STREAK, 2.4f, S * 0.5f);
        }
    }

    private void emitTrails() {
        float t = now - rollT0;
        for (int i = 0; i < animCount; i++) {
            Anim an = anims[i];
            float tf = t - F0 - an.delay;
            if (tf < 0 || tf > FD - 0.2f) continue;
            if (animCount > 4 && (i & 1) == 1) continue;
            float g = Math.min(1, an.speed / (S * 1.5f));
            if (animCount <= 4 || rng.nextFloat() < 0.4f) {
                fog(an.x + (rng.nextFloat() - 0.5f) * an.sz * 0.5f, an.y + (rng.nextFloat() - 0.5f) * an.sz * 0.5f,
                        (rng.nextFloat() - 0.5f) * S * 0.04f, (rng.nextFloat() - 0.5f) * S * 0.04f,
                        0.7f + rng.nextFloat() * 0.5f, an.sz * (0.3f + 0.25f * g), FOG);
            }
            int n = rng.nextFloat() < g ? 2 : 1;
            for (int k = 0; k < n; k++) {
                float r = an.sz * 0.6f;
                spawn(an.x + (rng.nextFloat() - 0.5f) * r, an.y + (rng.nextFloat() - 0.5f) * r,
                        (rng.nextFloat() - 0.5f) * S * 0.1f, (rng.nextFloat() - 0.5f) * S * 0.1f,
                        0.25f + rng.nextFloat() * 0.3f, S * (0.004f + rng.nextFloat() * 0.005f),
                        HAZE[rng.nextInt(HAZE.length)], PT_GLOW, 3f, -S * 0.05f);
            }
        }
    }

    /** Natural 1: a fiery streak plunges from the DC into the die. */
    private void emitFlameStreak(float vt) {
        float k = vt / 0.32f;
        Anim m = mainAnim();
        float y0 = S * HEAD_NUM_Y - S * 0.02f;
        float y = lerp(y0, m.y, k);
        for (int i = 0; i < 6; i++) {
            spawn(cx + (rng.nextFloat() - 0.5f) * S * 0.04f, y + (rng.nextFloat() - 0.5f) * S * 0.03f,
                    (rng.nextFloat() - 0.5f) * S * 0.2f, -S * (0.1f + rng.nextFloat() * 0.25f),
                    0.3f + rng.nextFloat() * 0.4f, S * (0.008f + rng.nextFloat() * 0.01f),
                    rng.nextFloat() < 0.5f ? EMBER : 0xFFFF3B5C, PT_GLOW, 2f, -S * 0.2f);
        }
        if (k > 0.85f && rng.nextFloat() < 0.6f) sparkBurst(m.x, m.y, 6, S * 0.6f, EMBER);
    }

    /** Natural 1: dark red smoke curling around the die. */
    private void emitSmoke(float dt, float vt) {
        if (vt > 6) return;
        Anim m = mainAnim();
        float rate = vt < 1.5f ? 16 : 5;
        float n = rate * dt;
        int count = (int) n + (rng.nextFloat() < n - (int) n ? 1 : 0);
        for (int i = 0; i < count; i++) {
            double a = rng.nextDouble() * Math.PI * 2;
            float r = m.sz * (0.85f + rng.nextFloat() * 0.35f);
            spawn(m.x + (float) Math.cos(a) * r, m.y + (float) Math.sin(a) * r,
                    (float) Math.cos(a) * S * 0.05f, -S * (0.03f + rng.nextFloat() * 0.05f),
                    1.2f + rng.nextFloat() * 1.0f, S * (0.025f + rng.nextFloat() * 0.025f),
                    rng.nextFloat() < 0.5f ? 0xFF3A0E4A : 0xFF12061A, PT_SMOKE, 0.8f, 0);
        }
    }

    private void fog(float x, float y, float vx, float vy, float life, float size, int[] palette) {
        spawn(x, y, vx, vy, life, size, palette[rng.nextInt(palette.length)], PT_FOG, 1.2f, -S * 0.02f);
    }

    /** Thin delerium smoke curling up off the resting crystal. */
    private void emitIdleSmoke(float dt) {
        if (idleA < 0.5f || rng.nextFloat() > 6 * dt) return;
        float size = idleSize();
        fog(cx + (rng.nextFloat() - 0.5f) * size * 0.8f, idleY() - size * 0.55f,
                (rng.nextFloat() - 0.5f) * S * 0.04f, -S * (0.05f + rng.nextFloat() * 0.05f),
                1.6f + rng.nextFloat(), S * (0.02f + rng.nextFloat() * 0.015f), FOG);
    }

    /** A puff ring bursting out from under a die as it lands. */
    private void fogRing(float x, float y, float size, int n, int[] palette) {
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + rng.nextFloat() * 0.3;
            float sp = S * (0.25f + rng.nextFloat() * 0.2f);
            fog(x + (float) Math.cos(a) * size * 0.5f, y + (float) Math.sin(a) * size * 0.4f,
                    (float) Math.cos(a) * sp, (float) Math.sin(a) * sp * 0.6f,
                    1.1f + rng.nextFloat() * 0.6f, size * (0.28f + rng.nextFloat() * 0.12f), palette);
        }
    }

    /** The Haze: multicoloured motes drifting lazily across the whole screen. */
    private void emitHaze(float dt) {
        float n = 7 * dt;
        if (rng.nextFloat() > n) return;
        double a = rng.nextDouble() * Math.PI * 2;
        float r = (float) Math.sqrt(rng.nextFloat()) * S * 0.46f;
        spawn(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
                (rng.nextFloat() - 0.5f) * S * 0.05f, -S * (0.01f + rng.nextFloat() * 0.03f),
                3f + rng.nextFloat() * 3f, S * (0.004f + rng.nextFloat() * 0.006f),
                HAZE[rng.nextInt(HAZE.length)], PT_MOTE, 0.05f, 0);
    }

    private void emitGoldMotes(float dt) {
        Anim m = mainAnim();
        if (rng.nextFloat() < dt * 5) {
            fog(m.x + (rng.nextFloat() - 0.5f) * m.sz, m.y - m.sz * 0.4f, (rng.nextFloat() - 0.5f) * S * 0.04f,
                    -S * 0.06f, 1.8f, S * 0.03f, FOG_PURE);
        }
        if (rng.nextFloat() > dt * 18) return;
        double a = rng.nextDouble() * Math.PI * 2;
        float r = m.sz * (0.7f + rng.nextFloat() * 0.5f);
        spawn(m.x + (float) Math.cos(a) * r, m.y + (float) Math.sin(a) * r, 0, -S * (0.04f + rng.nextFloat() * 0.06f),
                0.9f + rng.nextFloat() * 0.7f, S * 0.006f, rng.nextBoolean() ? CYAN_HI : GOLD_HI, PT_GLOW, 0.5f, 0);
    }

    /** smokePass: only smoke (drawn behind the dice); otherwise everything else (in front). */
    private void drawParticles(Canvas c, float dt, boolean smokePass) {
        fill.setShader(null);
        float a = state == IDLE ? rollA : 1;
        for (int i = 0; i < P_MAX; i++) {
            if (pLife[i] <= 0) continue;
            boolean back = pType[i] == PT_SMOKE || pType[i] == PT_MOTE || pType[i] == PT_FOG;
            if (back != smokePass) continue;
            pLife[i] -= dt;
            float drag = (float) Math.exp(-dt * pDrag[i]);
            pVX[i] *= drag;
            pVY[i] = pVY[i] * drag + pGrav[i] * dt;
            float ox = pX[i], oy = pY[i];
            pX[i] += pVX[i] * dt;
            pY[i] += pVY[i] * dt;
            float k = Math.max(0, pLife[i] / pMax[i]);
            switch (pType[i]) {
                case PT_STREAK:
                    line.setStrokeWidth(pSize[i] * (0.5f + k));
                    line.setColor(withAlpha(pColor[i], (int) (255 * k * a)));
                    c.drawLine(ox - (pX[i] - ox) * 1.8f, oy - (pY[i] - oy) * 1.8f, pX[i], pY[i], line);
                    break;
                case PT_GLOW:
                    drawGlow(c, pX[i], pY[i], pSize[i] * 3f, pColor[i], 0.7f * k * a);
                    fill.setColor(withAlpha(pColor[i], (int) (255 * k * a)));
                    c.drawCircle(pX[i], pY[i], pSize[i] * 0.45f * (0.4f + k), fill);
                    break;
                case PT_FOG: {
                    float age = 1 - k;
                    float fa = 0.34f * (float) Math.pow(Math.sin(Math.PI * age), 0.8);
                    drawGlow(c, pX[i], pY[i], pSize[i] * (1 + 1.8f * age), pColor[i], fa);
                    break;
                }
                case PT_MOTE: {
                    float fa = (float) Math.sin(Math.PI * k) * 0.55f;
                    float tw = 0.7f + 0.3f * (float) Math.sin(now * 5 + i);
                    drawGlow(c, pX[i], pY[i], pSize[i] * 3.2f, pColor[i], fa * tw);
                    fill.setColor(withAlpha(pColor[i], (int) (255 * fa * tw)));
                    c.drawCircle(pX[i], pY[i], pSize[i] * 0.4f, fill);
                    break;
                }
                default: // smoke: grows and fades in-out
                    float grow = 1 + (1 - k) * 1.6f;
                    fill.setColor(withAlpha(pColor[i], (int) (75 * k * (1 - k) * 4 * a)));
                    c.drawCircle(pX[i], pY[i], pSize[i] * grow, fill);
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ 3D die

    /**
     * Flat-shaded visible faces, edges, and numbers mapped onto faces with a perspective homography.
     * style: 0 pearl, 1 ruby, 2 crit gold (blended from the skin by mix). redFace: face whose number
     * burns red (natural 1), -1 for none.
     */
    private void drawDie(Canvas c, Poly p, int kind, Quat q, float x, float y, float size, float alpha,
                         int style, float mix, int redFace) {
        if (alpha <= 0.01f || size < 1) return;
        int nv = p.v.length;
        for (int i = 0; i < nv; i++) {
            q.rotate(p.v[i], pv);
            float k = CAM / (CAM - pv[2]);
            projX[i] = x + pv[0] * size * k;
            projY[i] = y - pv[1] * size * k;
        }
        int from = style == 2 ? roller.skin : style;
        int er = mixC(EDGE[from][0], EDGE[style][0], mix), eg = mixC(EDGE[from][1], EDGE[style][1], mix),
                eb = mixC(EDGE[from][2], EDGE[style][2], mix);
        int nr = mixC(NUM[from][0], NUM[style][0], mix), ng = mixC(NUM[from][1], NUM[style][1], mix),
                nb = mixC(NUM[from][2], NUM[style][2], mix);
        boolean crystalSkin = from == 0;
        boolean darkNumbers = style == 2 && mix > 0.5f;
        boolean corrupted = redFace >= 0;
        if (corrupted) { er = 200; eg = 40; eb = 90; }
        int a = (int) (alpha * 255);
        edge.setStrokeWidth(Math.max(1f, size * (crystalSkin ? 0.022f : 0.026f)));
        edge.setColor(Color.argb((int) (a * 0.9f), er, eg, eb));
        float shimmer = 12 * (float) Math.sin(now * 0.7f);

        for (int fi = 0; fi < p.faces.length; fi++) {
            Poly.Face f = p.faces[fi];
            q.rotate(f.n, pv);
            q.rotate(f.c, pv2);
            float vis = CAM * pv[2] - (pv[0] * pv2[0] + pv[1] * pv2[1] + pv[2] * pv2[2]);
            if (vis <= 0) continue;
            float diff = Math.max(0, pv[0] * LIGHT[0] + pv[1] * LIGHT[1] + pv[2] * LIGHT[2]);
            float spec = (float) Math.pow(Math.max(0, pv[0] * HALF[0] + pv[1] * HALF[1] + pv[2] * HALF[2]),
                    crystalSkin ? 22 : 30) * (crystalSkin ? 0.7f : 0.55f);
            float sh = 0.22f + 0.85f * diff;
            float fr, fg, fb;
            if (crystalSkin) {
                // iridescent delerium: the hue swings magenta → violet → teal with the facet angle
                int ic = hsvToRgb(258 + 62 * pv[0] + 28 * pv[1] + shimmer, 0.6f, 0.3f + 0.62f * diff);
                fr = (ic >> 16) & 255; fg = (ic >> 8) & 255; fb = ic & 255;
            } else {
                fr = BASE[from][0] * sh; fg = BASE[from][1] * sh; fb = BASE[from][2] * sh;
            }
            if (style == 2) {
                fr = lerp(fr, BASE[2][0] * (0.45f + 0.6f * diff), mix);
                fg = lerp(fg, BASE[2][1] * (0.45f + 0.6f * diff), mix);
                fb = lerp(fb, BASE[2][2] * (0.45f + 0.6f * diff), mix);
            }
            if (corrupted) { fr *= 0.45f; fg *= 0.28f; fb *= 0.4f; }
            int r = clamp255(fr + 255 * spec), g = clamp255(fg + 245 * spec), b = clamp255(fb + 255 * spec);
            face.setColor(Color.argb(a, r, g, b));
            poly.reset();
            for (int j = 0; j < f.idx.length; j++) {
                int vi = f.idx[j];
                if (j == 0) poly.moveTo(projX[vi], projY[vi]); else poly.lineTo(projX[vi], projY[vi]);
            }
            poly.close();
            c.drawPath(poly, face);
            c.drawPath(poly, edge);

            float facing = pv[2];
            float minFacing = size < S * 0.09f ? 0.55f : 0.3f;
            if (facing < minFacing) continue;
            float ta = Math.min(1, (facing - minFacing) / 0.3f);
            float s = f.inr * p.textScale;
            for (int corner = 0; corner < 4; corner++) {
                float ux = src[corner * 2], uy = -src[corner * 2 + 1];
                pv[0] = f.c[0] + s * (ux * f.right[0] + uy * f.up[0]);
                pv[1] = f.c[1] + s * (ux * f.right[1] + uy * f.up[1]);
                pv[2] = f.c[2] + s * (ux * f.right[2] + uy * f.up[2]);
                q.rotate(pv, pv2);
                float k = CAM / (CAM - pv2[2]);
                dst[corner * 2] = x + pv2[0] * size * k;
                dst[corner * 2 + 1] = y - pv2[1] * size * k;
            }
            if (!homo.setPolyToPoly(src, 0, dst, 0, 4)) continue;
            labelPaths[kind][fi].transform(homo, tmpPath);
            boolean red = fi == redFace;
            if (!darkNumbers || red) {
                // dark emboss under light numbers
                label.setColor(Color.argb((int) (a * ta * 0.5f), 0, 0, 0));
                tmpPath.offset(size * 0.008f, size * 0.012f);
                c.drawPath(tmpPath, label);
                tmpPath.offset(-size * 0.008f, -size * 0.012f);
            } else {
                // engraved look: a light rim under dark numbers
                label.setColor(Color.argb((int) (a * ta * 0.5f), 255, 255, 255));
                tmpPath.offset(size * 0.006f, size * 0.009f);
                c.drawPath(tmpPath, label);
                tmpPath.offset(-size * 0.006f, -size * 0.009f);
            }
            if (red) label.setColor(Color.argb((int) (a * ta), FUMBLE_NUM[0], FUMBLE_NUM[1], FUMBLE_NUM[2]));
            else label.setColor(Color.argb((int) (a * ta), nr, ng, nb));
            c.drawPath(tmpPath, label);
        }
    }

    /** Gradient shaders ignore the paint colour on Android, so each glow colour gets its own gradient. */
    private final java.util.HashMap<Integer, RadialGradient> glowShaders = new java.util.HashMap<>();

    private void drawGlow(Canvas c, float x, float y, float r, int color, float alpha) {
        if (alpha <= 0.01f || r < 1) return;
        int rgb = color & 0xFFFFFF;
        RadialGradient g = glowShaders.get(rgb);
        if (g == null) {
            g = new RadialGradient(0, 0, 1, new int[]{0xFF000000 | rgb, 0x55000000 | rgb, rgb},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            glowShaders.put(rgb, g);
        }
        shaderM.setScale(r, r);
        shaderM.postTranslate(x, y);
        g.setLocalMatrix(shaderM);
        glow.setShader(g);
        glow.setAlpha((int) (Math.min(1, alpha) * 255));
        c.drawCircle(x, y, r, glow);
    }

    // ------------------------------------------------------------------ history

    private float rowH() { return S * 0.15f; }

    private void clampHistory() {
        float max = Math.max(0, (roller.history.size() + 1) * rowH() - S * 0.55f);
        if (histScroll < 0) { histScroll = 0; histVel = 0; }
        if (histScroll > max) { histScroll = max; histVel = 0; }
    }

    private void drawHistory(Canvas c) {
        float off = histX * S;
        c.save();
        c.translate(off, 0);
        fill.setShader(null);
        fill.setColor(0xF2070509);
        c.drawRect(0, 0, getWidth(), getHeight(), fill);
        line.setStrokeWidth(S * 0.004f);
        line.setColor(GOLD_DIM);
        c.drawCircle(cx, cy, S * 0.488f, line);

        float y0 = S * 0.2f - histScroll;
        text.setTextSize(S * 0.045f);
        text.setLetterSpacing(0.15f);
        text.setColor(GOLD);
        c.drawText("ИСТОРИЯ", cx, y0, text);
        text.setLetterSpacing(0);

        if (roller.history.isEmpty()) {
            text.setTextSize(S * 0.045f);
            text.setColor(MUTED);
            c.drawText("Бросков пока нет", cx, cy + S * 0.02f, text);
        }
        for (int i = 0; i < roller.history.size(); i++) {
            float y = y0 + rowH() * (i + 1);
            if (y < -rowH() || y > S + rowH()) continue;
            String[] p = roller.history.get(i).split("\\|", -1);
            if (p.length < 6) continue;
            String expr = p[0], total = p[1], flags = p[2], br = p[3], dcs = p[4];
            long ts;
            try { ts = Long.parseLong(p[5]); } catch (NumberFormatException e) { ts = 0; }
            float fade = edgeFade(y);
            if (fade <= 0) continue;
            int a = (int) (fade * 255);
            int col = flags.contains("C") ? GOLD_HI : flags.contains("F") ? RED : IVORY;

            serif.setTextSize(S * 0.075f);
            float tw = serif.measureText(total);
            text.setTextSize(S * 0.04f);
            String left = expr + (flags.contains("A") ? " ↑" : flags.contains("D") ? " ↓" : "") + "  ";
            float lw = text.measureText(left);
            float startX = cx - (lw + tw) / 2;
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(withAlpha(MUTED, a));
            c.drawText(left, startX, y, text);
            text.setTextAlign(Paint.Align.CENTER);
            serif.setTextAlign(Paint.Align.LEFT);
            serif.setColor(withAlpha(col, a));
            c.drawText(total, startX + lw, y, serif);
            serif.setTextAlign(Paint.Align.CENTER);

            StringBuilder sub = new StringBuilder();
            if (!br.isEmpty()) sub.append(br).append("  ·  ");
            if (flags.contains("C")) sub.append("крит!  ·  ");
            else if (flags.contains("F")) sub.append("провал!  ·  ");
            else if (flags.contains("P")) sub.append("успех СЛ ").append(dcs).append("  ·  ");
            else if (flags.contains("X")) sub.append("провал СЛ ").append(dcs).append("  ·  ");
            sub.append(ts > 0 ? timeFmt.format(new Date(ts)) : "");
            String subS = sub.toString();
            text.setTextSize(fitSize(subS, S * 0.032f, S * 0.72f, text));
            text.setColor(withAlpha(0xFF74697F, a));
            c.drawText(subS, cx, y + S * 0.045f, text);

            line.setStrokeWidth(1);
            line.setColor(withAlpha(0xFF2E2838, a));
            c.drawLine(cx - S * 0.25f, y + S * 0.08f, cx + S * 0.25f, y + S * 0.08f, line);
        }

        if (clearConfirm) {
            fill.setColor(0xE6000000);
            c.drawCircle(cx, cy, S * 0.5f, fill);
            rect.set(cx - S * 0.26f, cy - S * 0.07f, cx + S * 0.26f, cy + S * 0.07f);
            fill.setColor(0xFF6A1418);
            c.drawRoundRect(rect, S * 0.07f, S * 0.07f, fill);
            line.setStrokeWidth(S * 0.005f);
            line.setColor(RED);
            c.drawRoundRect(rect, S * 0.07f, S * 0.07f, line);
            text.setTextSize(S * 0.048f);
            text.setColor(IVORY);
            c.drawText("Очистить", cx, cy + S * 0.017f, text);
            text.setTextSize(S * 0.034f);
            text.setColor(MUTED);
            c.drawText("тап мимо — отмена", cx, cy + S * 0.14f, text);
        }
        c.restore();
    }

    private float edgeFade(float y) {
        float top = S * 0.08f, bot = S * 0.92f, band = S * 0.12f;
        if (y < top || y > bot) return 0;
        return Math.min(1, Math.min((y - top) / band, (bot - y) / band));
    }

    // ------------------------------------------------------------------ settings

    private static final float SET_SOUND = 0.33f, SET_SHAKE = 0.47f, SET_SKIN = 0.61f, SET_DONE = 0.765f;

    private void drawSettings(Canvas c) {
        int a = (int) (settingsA * 255);
        fill.setShader(null);
        fill.setColor(withAlpha(0xFF070509, (int) (a * 0.96f)));
        c.drawCircle(cx, cy, S * 0.75f, fill);
        text.setTextSize(S * 0.042f);
        text.setLetterSpacing(0.15f);
        text.setColor(withAlpha(GOLD, a));
        c.drawText("НАСТРОЙКИ", cx, S * 0.2f, text);
        text.setLetterSpacing(0);
        settingRow(c, S * SET_SOUND, "Звук", roller.sound ? "ВКЛ" : "ВЫКЛ", roller.sound, a);
        settingRow(c, S * SET_SHAKE, "Встряхнуть — бросок", roller.shake ? "ВКЛ" : "ВЫКЛ", roller.shake, a);
        settingRow(c, S * SET_SKIN, "Кубик", roller.skin == 0 ? "ДЕЛИРИУМ" : "ОБСИДИАН", true, a);
        drawPill(c, cx, S * SET_DONE, S * 0.34f, S * 0.095f, true, "Готово", S * 0.045f, a);
    }

    private void settingRow(Canvas c, float y, String name, String value, boolean on, int a) {
        rect.set(cx - S * 0.37f, y - S * 0.055f, cx + S * 0.37f, y + S * 0.055f);
        fill.setColor(withAlpha(0xFF17121F, a));
        c.drawRoundRect(rect, S * 0.055f, S * 0.055f, fill);
        line.setStrokeWidth(S * 0.004f);
        line.setColor(withAlpha(on ? GOLD : GOLD_DIM, a));
        c.drawRoundRect(rect, S * 0.055f, S * 0.055f, line);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(S * 0.036f);
        text.setColor(withAlpha(on ? GOLD_HI : MUTED, a));
        float vw = text.measureText(value);
        c.drawText(value, cx + S * 0.32f, y + S * 0.013f, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(fitSize(name, S * 0.038f, S * 0.6f - vw, text));
        text.setColor(withAlpha(IVORY, a));
        c.drawText(name, cx - S * 0.32f, y + S * 0.013f, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = lastY = e.getY();
                downTime = e.getEventTime();
                longFired = false;
                moved = false;
                dragging = false;
                histVel = 0;
                postDelayed(longPress, 550);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (!moved && Math.hypot(dx, dy) > S * 0.05f) {
                    moved = true;
                    removeCallbacks(longPress);
                    if (historyOpen && !clearConfirm && Math.abs(dy) > Math.abs(dx)) dragging = true;
                }
                if (dragging) {
                    float d = lastY - e.getY();
                    histScroll += d;
                    long dtm = Math.max(1, e.getEventTime() - (e.getHistorySize() > 0 ? e.getHistoricalEventTime(0) : downTime));
                    histVel = d / (dtm / 1000f);
                    clampHistory();
                }
                lastY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPress);
                if (longFired) return true;
                float dx = e.getX() - downX, dy = e.getY() - downY;
                if (dragging) {
                    dragging = false;
                    return true;
                }
                if (!moved && e.getEventTime() - downTime < 500) onTap(downX, downY);
                else if (moved) onSwipe(dx, dy);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                dragging = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void onTap(float x, float y) {
        if (settingsOpen) {
            if (Math.abs(y - S * SET_SOUND) < S * 0.065f) {
                roller.sound = !roller.sound;
                sfx.soundOn = roller.sound;
                sfx.select();
            } else if (Math.abs(y - S * SET_SHAKE) < S * 0.065f) {
                roller.shake = !roller.shake;
                if (sensors != null && accel != null) {
                    if (roller.shake) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                    else sensors.unregisterListener(this);
                }
                sfx.select();
            } else if (Math.abs(y - S * SET_SKIN) < S * 0.065f) {
                roller.skin = 1 - roller.skin;
                idleSwapAt = now;
                sfx.select();
            } else if (y > S * 0.69f) {
                settingsOpen = false;
                sfx.select();
            }
            roller.save();
            return;
        }
        if (historyOpen) {
            if (clearConfirm) {
                if (Math.abs(x - cx) < S * 0.26f && Math.abs(y - cy) < S * 0.08f) {
                    roller.clearHistory();
                    histScroll = 0;
                    sfx.select();
                }
                clearConfirm = false;
            }
            return;
        }
        if (state == ROLLING) {
            fastForward();
            return;
        }
        if (state == RESULT) {
            if (now - revealT0 < VERDICT_T) fastForward();
            else if (hitDieArea(x, y)) startRoll();
            else {
                state = IDLE;
                idleSwapAt = now;
                sfx.tick();
            }
            return;
        }
        // IDLE
        if (Math.hypot(x - cx, y - S * DIE_Y) < S * 0.17f) {
            startRoll();
            return;
        }
        if (Math.abs(y - S * 0.27f) < S * 0.09f && Math.abs(x - cx) < S * 0.22f) {
            if (active != Roller.P_DC) select(Roller.P_DC);
            else adjust(x < cx ? -1 : 1);
            return;
        }
        if (Math.hypot(x - leftX(), y - S * DIE_Y) < sideR() * 1.4f) {
            if (active != Roller.P_COUNT) select(Roller.P_COUNT);
            else adjust(y < S * DIE_Y ? 1 : -1);
            return;
        }
        if (Math.hypot(x - rightX(), y - S * DIE_Y) < sideR() * 1.4f) {
            if (active != Roller.P_MOD) select(Roller.P_MOD);
            else adjust(y < S * DIE_Y ? 1 : -1);
            return;
        }
        if (Math.abs(y - pillY()) < S * 0.065f) {
            if (modeShown() && x > cx) {
                active = Roller.P_MODE;
                adjust(1);
                return;
            }
            if (Math.abs(x - dieChipX()) < S * 0.14f) {
                if (active != Roller.P_DIE) select(Roller.P_DIE);
                else adjust(x < dieChipX() ? -1 : 1);
            }
        }
    }

    private boolean hitDieArea(float x, float y) {
        return y > S * 0.36f && y < S * 0.7f && Math.abs(x - cx) < S * 0.3f;
    }

    private void onSwipe(float dx, float dy) {
        if (settingsOpen || clearConfirm) return;
        if (Math.abs(dx) > Math.abs(dy)) {
            if (dx < 0 && !historyOpen && state != ROLLING) {
                historyOpen = true;
                histScroll = 0;
                sfx.tick();
            } else if (dx > 0 && historyOpen) {
                historyOpen = false;
                sfx.tick();
            }
            return;
        }
        if (historyOpen || state == ROLLING) return;
        if (state == RESULT) {
            state = IDLE;
            idleSwapAt = now;
        }
        adjust(dy < 0 ? 1 : -1);
    }

    private void onLongPress() {
        if (state == ROLLING) return;
        if (historyOpen) {
            if (!roller.history.isEmpty()) clearConfirm = true;
        } else if (!settingsOpen) {
            settingsOpen = true;
        }
        sfx.select();
    }

    /** Bezel / crown rotation. Positive delta = clockwise. */
    boolean onRotary(float delta) {
        if (settingsOpen) return true;
        if (historyOpen) {
            histScroll += delta * S * 0.09f;
            histVel = 0;
            clampHistory();
            return true;
        }
        if (state == ROLLING) return true;
        rotaryAcc += delta;
        int steps = (int) rotaryAcc;
        if (steps == 0) return true;
        rotaryAcc -= steps;
        if (state == RESULT) {
            state = IDLE;
            idleSwapAt = now;
            rotaryAcc = 0;
            sfx.tick();
            return true;
        }
        adjust(steps > 0 ? 1 : -1);
        return true;
    }

    boolean onBack() {
        if (settingsOpen) { settingsOpen = false; return true; }
        if (clearConfirm) { clearConfirm = false; return true; }
        if (historyOpen) { historyOpen = false; return true; }
        if (state == RESULT) { state = IDLE; idleSwapAt = now; return true; }
        return false;
    }

    private void select(int param) {
        active = param;
        sfx.select();
    }

    private void adjust(int delta) {
        int before = roller.dieIndex;
        if (roller.adjust(active, delta)) {
            if (roller.dieIndex != before) {
                idleSwapAt = now;
                if (!roller.modeAvailable() && active == Roller.P_MODE) active = Roller.P_DIE;
            }
            sfx.tick();
            roller.save();
        }
    }

    // ------------------------------------------------------------------ shake

    @Override
    public void onSensorChanged(SensorEvent ev) {
        float x = ev.values[0], y = ev.values[1], z = ev.values[2];
        float g = (float) Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
        long t = System.currentTimeMillis();
        if (g > 2.4f && t - lastShake > 1500) {
            lastShake = t;
            if (state != ROLLING && !historyOpen && !settingsOpen) startRoll();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ------------------------------------------------------------------ utils

    private static float fitSize(String s, float size, float maxW, Paint p) {
        p.setTextSize(size);
        float w = p.measureText(s);
        return w > maxW ? size * maxW / w : size;
    }

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    private static int withAlpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF);
    }

    private static int mixColor(int a, int b, float t) {
        int r = mixC((a >> 16) & 255, (b >> 16) & 255, t), g = mixC((a >> 8) & 255, (b >> 8) & 255, t),
                bl = mixC(a & 255, b & 255, t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static int mixC(int a, int b, float t) { return (int) (a + (b - a) * t); }

    private static int hsvToRgb(float h, float s, float v) {
        h = ((h % 360) + 360) % 360;
        v = clamp01(v);
        float c = v * s, x = c * (1 - Math.abs((h / 60) % 2 - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; } else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return 0xFF000000 | ((int) ((r + m) * 255) << 16) | ((int) ((g + m) * 255) << 8) | (int) ((b + m) * 255);
    }

    private static int clamp255(float v) { return (int) Math.max(0, Math.min(255, v)); }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static float clamp01(float v) { return clamp(v, 0, 1); }

    private static float smooth(float t) {
        t = clamp01(t);
        return t * t * (3 - 2 * t);
    }

    private static float easeOut(float t) { return 1 - (1 - t) * (1 - t); }

    private static float easeInOut(float t) { return t < 0.5f ? 2 * t * t : 1 - (float) Math.pow(-2 * t + 2, 2) / 2; }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
