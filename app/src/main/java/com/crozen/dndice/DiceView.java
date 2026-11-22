package com.crozen.dndice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
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
 * The whole app on one Canvas: a real-time 3D die (flat-shaded polyhedron with numbers mapped
 * onto its faces in perspective), the throw animation with bounces, the result reveal with
 * crit / fumble effects, the parameter chips, the history list and the settings overlay.
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

    // time
    private long lastNs;
    private float now;
    private boolean running;

    // smoothed transition values
    private float chipsA = 1, idleA = 1, rollA, histX = 1, settingsA, flashA, redA, ringBoost;
    private float idleSwapAt = -10, fumbleShakeAt = -10;

    // ------------------------------------------------------------------ roll animation

    private static final float[] PHASE_D = {0.36f, 0.24f, 0.16f, 0.10f};
    private static final float[] PHASE_H = {1f, 0.42f, 0.17f, 0.06f};
    private static final float T_SPIN = 1.15f, T_POS = 0.85f, T_REVEAL = 1.22f;

    private static final class Anim {
        int kind;
        Poly poly;
        final Quat qStart = new Quat(), qFinal = new Quat(), delta = new Quat();
        final float[] ax1 = new float[3], ax2 = new float[3];
        float a1, a2, delay, heightScale;
        float startX, startY, startSize, fx, fy, size;
        boolean dropped;
        // per-frame
        float x, y, sz, h, baseY;
        final Quat q = new Quat();
    }

    private final Anim[] anims = new Anim[Roller.MAX_COUNT];
    private int animCount;
    private Roller.Result result;
    private float rollT0, revealT0, revealDelay, verdictAt = -1;
    private final float[] impT = new float[64], impS = new float[64];
    private int impN, impIdx;

    // sparks
    private static final int SPARKS = 70;
    private final float[] spX = new float[SPARKS], spY = new float[SPARKS], spVX = new float[SPARKS],
            spVY = new float[SPARKS], spLife = new float[SPARKS], spMax = new float[SPARKS];
    private int sparkColor;

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

    // shake
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
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint();
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient glowShader, shadowShader, redShader;
    private final Matrix shaderM = new Matrix();
    private final Matrix homo = new Matrix();
    private final Path poly = new Path(), tmpPath = new Path();
    private final float[] src = {-1, -1, 1, -1, 1, 1, -1, 1};
    private final float[] dst = new float[8];
    private final float[] pv = new float[3], pv2 = new float[3];
    private final float[] projX = new float[24], projY = new float[24];
    private final RectF rect = new RectF();
    private final Quat tq1 = new Quat(), tq2 = new Quat(), tq3 = new Quat(), idleQ = new Quat();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private static final float CAM = 4f;
    private static final float[] LIGHT = norm(-0.38f, 0.58f, 0.72f);
    private static final float[] HALF = norm(-0.38f, 0.58f, 1.72f);

    // palette
    private static final int GOLD = 0xFFE8B95A, GOLD_HI = 0xFFFFE3A0, GOLD_DIM = 0xFF6E5530,
            IVORY = 0xFFF4E6C8, MUTED = 0xFF9C8B78, CRIMSON = 0xFFD8434E, EMBER = 0xFFFF6A4E;
    private static final int[][] BASE = {{150, 26, 40}, {236, 182, 66}, {64, 60, 66}};
    private static final int[][] EDGE = {{232, 185, 90}, {255, 244, 200}, {178, 60, 44}};
    private static final int[][] NUM = {{255, 226, 160}, {74, 32, 4}, {255, 110, 88}};

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
        dashed.setStyle(Paint.Style.STROKE);

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
        bg.setShader(new RadialGradient(cx, cy * 0.92f, S * 0.62f,
                new int[]{0xFF2A1316, 0xFF140A0C, 0xFF050304}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        glowShader = new RadialGradient(0, 0, 1, new int[]{0xFFFFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        shadowShader = new RadialGradient(0, 0, 1, new int[]{0xAA000000, 0x00000000}, null, Shader.TileMode.CLAMP);
        redShader = new RadialGradient(cx, cy, S * 0.55f, new int[]{0x00000000, 0x00000000, 0xFFB01010},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glow.setShader(glowShader);
        shadow.setShader(shadowShader);
        dashed.setPathEffect(new DashPathEffect(new float[]{S * 0.006f, S * 0.014f}, 0));
    }

    // ------------------------------------------------------------------ frame

    @Override
    protected void onDraw(Canvas c) {
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        now += dt;
        update(dt);

        c.drawRect(0, 0, getWidth(), getHeight(), bg);

        c.save();
        float shakeT = now - fumbleShakeAt;
        if (shakeT < 0.45f) {
            float a = S * 0.018f * (1 - shakeT / 0.45f);
            c.translate((float) Math.sin(shakeT * 70) * a, (float) Math.cos(shakeT * 53) * a * 0.5f);
        }

        drawFrame(c);
        if (idleA > 0.01f) drawIdle(c);
        if (rollA > 0.01f && animCount > 0) drawRoll(c);
        drawSparks(c, dt);
        if (chipsA > 0.01f) drawChips(c);
        c.restore();

        if (flashA > 0.01f) {
            fill.setShader(null);
            fill.setColor(Color.argb((int) (flashA * 150), 255, 236, 180));
            c.drawCircle(cx, cy, S * 0.75f, fill);
        }
        if (redA > 0.01f) {
            fill.setShader(redShader);
            fill.setAlpha((int) (redA * 200));
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
        redA *= (float) Math.exp(-dt * 2.6);
        ringBoost += ((state == ROLLING ? 1 : 0) - ringBoost) * (1 - (float) Math.exp(-dt * 4));

        if (!historyOpen || dragging) {
            histVel = dragging ? histVel : 0;
        } else {
            histScroll += histVel * dt;
            histVel *= (float) Math.exp(-dt * 4);
            clampHistory();
        }

        if (state == ROLLING) {
            float t = now - rollT0;
            while (impIdx < impN && impT[impIdx] <= t) {
                sfx.impact(impS[impIdx]);
                impIdx++;
            }
            if (t >= revealDelay) reveal();
        }
        if (verdictAt > 0 && now >= verdictAt) {
            verdictAt = -1;
            if (result != null) sfx.verdict(roller.passed(result));
        }
    }

    // ------------------------------------------------------------------ ornamental frame

    private void drawFrame(Canvas c) {
        float spin = now * (0.05f + ringBoost * 0.9f);
        int a = (int) (150 + 105 * ringBoost);
        line.setStrokeWidth(S * 0.0045f);
        line.setColor(withAlpha(GOLD_DIM, a));
        c.drawCircle(cx, cy, S * 0.488f, line);
        line.setStrokeWidth(S * 0.003f);
        c.drawCircle(cx, cy, S * 0.462f, line);

        c.save();
        c.rotate((float) Math.toDegrees(spin), cx, cy);
        line.setStrokeWidth(S * 0.004f);
        for (int i = 0; i < 48; i++) {
            float ang = (float) (i * Math.PI * 2 / 48);
            float r0 = S * (i % 4 == 0 ? 0.464f : 0.470f), r1 = S * 0.486f;
            float sn = (float) Math.sin(ang), cs = (float) Math.cos(ang);
            line.setColor(withAlpha(i % 4 == 0 ? GOLD : GOLD_DIM, a));
            c.drawLine(cx + cs * r0, cy + sn * r0, cx + cs * r1, cy + sn * r1, line);
        }
        fill.setShader(null);
        for (int i = 0; i < 8; i++) {
            float ang = (float) (i * Math.PI / 4 + Math.PI / 8);
            float r = S * 0.475f, d = S * 0.012f;
            float px = cx + (float) Math.cos(ang) * r, py = cy + (float) Math.sin(ang) * r;
            poly.reset();
            poly.moveTo(px, py - d);
            poly.lineTo(px + d * 0.7f, py);
            poly.lineTo(px, py + d);
            poly.lineTo(px - d * 0.7f, py);
            poly.close();
            fill.setColor(withAlpha(GOLD, a));
            c.drawPath(poly, fill);
        }
        c.restore();
    }

    /** Faint rotating magic circle behind a die. */
    private void drawMagicCircle(Canvas c, float x, float y, float r, float alpha, float rot) {
        if (alpha <= 0.01f) return;
        int a = (int) (alpha * 255);
        c.save();
        c.rotate((float) Math.toDegrees(rot), x, y);
        dashed.setStrokeWidth(S * 0.004f);
        dashed.setColor(withAlpha(GOLD, (int) (a * 0.55f)));
        c.drawCircle(x, y, r, dashed);
        line.setStrokeWidth(S * 0.0025f);
        line.setColor(withAlpha(GOLD_DIM, (int) (a * 0.8f)));
        c.drawCircle(x, y, r * 0.9f, line);
        // inscribed hexagram
        for (int t = 0; t < 2; t++) {
            poly.reset();
            for (int i = 0; i < 3; i++) {
                double ang = Math.PI * 2 * i / 3 + t * Math.PI / 3 - Math.PI / 2;
                float px = x + (float) Math.cos(ang) * r * 0.9f, py = y + (float) Math.sin(ang) * r * 0.9f;
                if (i == 0) poly.moveTo(px, py); else poly.lineTo(px, py);
            }
            poly.close();
            line.setColor(withAlpha(GOLD_DIM, (int) (a * 0.45f)));
            c.drawPath(poly, line);
        }
        c.restore();
    }

    // ------------------------------------------------------------------ idle die

    private int idleKind() {
        int s = roller.sides();
        return s == 100 ? Roller.KIND_TENS : Roller.kindFor(s);
    }

    private void computeIdleQ(Quat out) {
        Poly p = polys[idleKind()];
        Poly.Face f = p.faces[0];
        for (Poly.Face ff : p.faces) if (ff.value == (idleKind() == Roller.KIND_TENS ? 0 : p.sides)) f = ff;
        tq1.setAxisAngle(0, 1, 0, now * 0.55f);
        tq2.setAxisAngle(1, 0, 0, 0.42f);
        out.mul(tq1, tq2).mul(out, f.show);
    }

    private float idleSize() { return S * 0.185f; }

    private float idleY() { return cy + (float) Math.sin(now * 1.7f) * S * 0.008f; }

    private void drawIdle(Canvas c) {
        float pop = Math.min(1, (now - idleSwapAt) / 0.38f);
        float sc = 0.55f + 0.45f * easeOutBack(pop);
        float size = idleSize() * sc * (0.7f + 0.3f * idleA);
        float y = idleY();
        drawMagicCircle(c, cx, cy, S * 0.27f, idleA * 0.9f, -now * 0.15f);
        computeIdleQ(idleQ);
        drawGlow(c, cx, y, size * 1.9f, 0xFFB0303A, 0.33f * idleA);
        drawShadow(c, cx, y + size * 1.08f, size * 0.95f, 0.5f * idleA);
        drawDie(c, polys[idleKind()], idleKind(), idleQ, cx, y, size, idleA, 0, 0);

        // hint / expression under the die
        text.setTextSize(S * 0.042f);
        text.setColor(withAlpha(GOLD, (int) (chipsA * 230)));
        String hint = roller.history.isEmpty() ? "тап или встряхни" : roller.expression();
        c.drawText(hint, cx, S * 0.745f, text);
    }

    // ------------------------------------------------------------------ chips

    private float chipTopY() { return S * 0.145f; }

    private float sideChipR() { return S * 0.074f; }

    private float leftChipX() { return S * 0.118f; }

    private float rightChipX() { return S * 0.882f; }

    private float bottomY() { return S * 0.845f; }

    private boolean modeChipShown() { return roller.modeAvailable(); }

    private float dcChipX() { return modeChipShown() ? S * 0.365f : cx; }

    private float modeChipX() { return S * 0.635f; }

    private void drawChips(Canvas c) {
        int a = (int) (chipsA * 255);
        // die type
        String die = roller.sides() == 100 ? "d100" : "d" + roller.sides();
        drawPill(c, cx, chipTopY(), S * 0.30f, S * 0.1f, active == Roller.P_DIE, true, die, S * 0.062f, a);
        if (active == Roller.P_DIE) {
            text.setTextSize(S * 0.05f);
            text.setColor(withAlpha(GOLD, a));
            c.drawText("‹", cx - S * 0.115f, chipTopY() + S * 0.017f, text);
            c.drawText("›", cx + S * 0.115f, chipTopY() + S * 0.017f, text);
        }
        boolean countOn = roller.sides() != 100 && roller.effectiveMode() == Roller.MODE_NORMAL;
        String cnt = roller.effectiveCount() + "×";
        drawRoundChip(c, leftChipX(), cy, sideChipR(), active == Roller.P_COUNT, countOn, "КОЛ-ВО", cnt, a);
        String mod = roller.mod == 0 ? "0" : (roller.mod > 0 ? "+" : "−") + Math.abs(roller.mod);
        drawRoundChip(c, rightChipX(), cy, sideChipR(), active == Roller.P_MOD, true, "МОД", mod, a);
        String dc = roller.dc == 0 ? "СЛ —" : "СЛ " + roller.dc;
        drawPill(c, dcChipX(), bottomY(), S * 0.25f, S * 0.085f, active == Roller.P_DC, true, dc, S * 0.042f, a);
        if (modeChipShown()) {
            drawPill(c, modeChipX(), bottomY(), S * 0.25f, S * 0.085f, active == Roller.P_MODE, true,
                    roller.modeLabel(), S * 0.036f, a);
        }
    }

    private void drawPill(Canvas c, float x, float y, float w, float h, boolean on, boolean enabled,
                          String value, float size, int a) {
        rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
        fill.setShader(null);
        fill.setColor(withAlpha(on ? 0xFF3A1A1C : 0xFF1C1012, (int) (a * 0.92f)));
        c.drawRoundRect(rect, h / 2, h / 2, fill);
        line.setStrokeWidth(S * (on ? 0.007f : 0.004f));
        line.setColor(withAlpha(on ? GOLD : GOLD_DIM, a));
        c.drawRoundRect(rect, h / 2, h / 2, line);
        text.setTextSize(size);
        text.setColor(withAlpha(!enabled ? MUTED : on ? GOLD_HI : IVORY, enabled ? a : a / 2));
        c.drawText(value, x, y + size * 0.36f, text);
    }

    private void drawRoundChip(Canvas c, float x, float y, float r, boolean on, boolean enabled,
                               String caption, String value, int a) {
        fill.setShader(null);
        fill.setColor(withAlpha(on ? 0xFF3A1A1C : 0xFF1C1012, (int) (a * 0.92f)));
        c.drawCircle(x, y, r, fill);
        line.setStrokeWidth(S * (on ? 0.007f : 0.004f));
        line.setColor(withAlpha(on ? GOLD : GOLD_DIM, a));
        c.drawCircle(x, y, r, line);
        text.setTextSize(S * 0.027f);
        text.setColor(withAlpha(on ? GOLD : MUTED, a));
        c.drawText(caption, x, y - r * 0.38f, text);
        text.setTextSize(S * 0.058f);
        text.setColor(withAlpha(!enabled ? MUTED : on ? GOLD_HI : IVORY, enabled ? a : a / 2));
        c.drawText(value, x, y + r * 0.5f, text);
        if (on) {
            fill.setColor(withAlpha(GOLD, a));
            triangle(c, x, y - r - S * 0.022f, S * 0.016f, true);
            triangle(c, x, y + r + S * 0.022f, S * 0.016f, false);
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

    // ------------------------------------------------------------------ rolling

    private void startRoll() {
        if (state == ROLLING) return;
        if (historyOpen || settingsOpen) return;
        boolean fromIdle = state == IDLE;
        float startX = cx, startY = fromIdle ? idleY() : S * 0.40f;
        float startSize = fromIdle ? idleSize() : S * 0.12f;
        int prevKind = idleKind();
        if (fromIdle) computeIdleQ(idleQ);

        result = roller.roll();
        animCount = result.dice.size();
        float[][] lay = layout(animCount);
        impN = 0;
        impIdx = 0;
        float maxDelay = 0;
        for (int i = 0; i < animCount; i++) {
            Roller.DieRoll d = result.dice.get(i);
            Anim an = anims[i];
            an.kind = d.kind;
            an.poly = polys[d.kind];
            an.dropped = d.dropped;
            an.fx = cx + lay[i][0] * S;
            an.fy = S * 0.40f + lay[i][1] * S;
            an.size = lay[i][2] * S;
            an.startX = startX + (i == 0 ? 0 : (rng.nextFloat() - 0.5f) * S * 0.08f);
            an.startY = startY + (i == 0 ? 0 : (rng.nextFloat() - 0.5f) * S * 0.08f);
            an.startSize = startSize * (i == 0 ? 1f : 0.6f);
            an.delay = i == 0 ? 0 : rng.nextFloat() * 0.09f;
            an.heightScale = 0.8f + rng.nextFloat() * 0.4f;
            maxDelay = Math.max(maxDelay, an.delay);

            // final orientation: chosen face to the viewer, slight 3/4 tilt, a little in-plane twist
            Poly.Face f = an.poly.faceFor(d.value);
            float twist = (rng.nextFloat() - 0.5f) * (animCount > 1 ? 0.45f : 0.16f);
            tq1.setAxisAngle(1, 0, 0, 0.27f);
            tq2.setAxisAngle(0, 1, 0, -0.2f);
            tq3.setAxisAngle(0, 0, 1, twist);
            an.qFinal.mul(tq1, tq2).mul(an.qFinal, tq3).mul(an.qFinal, f.show);

            if (i == 0 && fromIdle && d.kind == prevKind) an.qStart.set(idleQ);
            else an.qStart.random(rng);

            Quat.randomAxis(rng, an.ax1);
            Quat.randomAxis(rng, an.ax2);
            an.a1 = (float) (Math.PI * 2 * (2.2 + rng.nextFloat() * 1.3));
            an.a2 = (float) (Math.PI * (0.8 + rng.nextFloat() * 1.2));
            // delta so that orientation(0) == qStart
            tq1.setAxisAngle(an.ax1[0], an.ax1[1], an.ax1[2], an.a1);
            tq2.setAxisAngle(an.ax2[0], an.ax2[1], an.ax2[2], an.a2);
            tq3.mul(an.qFinal, tq1).mul(tq3, tq2);
            an.delta.conj(tq3).mul(an.delta, an.qStart).normalize();

            // impact sounds
            float t = an.delay;
            int hits = i < 3 ? PHASE_D.length : 2;
            for (int k = 0; k < hits && impN < impT.length; k++) {
                t += PHASE_D[k];
                impT[impN] = t;
                impS[impN] = PHASE_H[k] * an.heightScale / (i == 0 ? 1f : 1.4f);
                impN++;
            }
        }
        sortImpacts();
        revealDelay = T_REVEAL + maxDelay;
        rollT0 = now;
        state = ROLLING;
        idleA = 0;
        rollA = 1;
        verdictAt = -1;
        for (int i = 0; i < SPARKS; i++) spLife[i] = 0;
        sfx.throwStart();
        if (!fromIdle) stepAnims(0);
    }

    private void sortImpacts() {
        for (int i = 1; i < impN; i++) {
            float t = impT[i], s = impS[i];
            int j = i - 1;
            while (j >= 0 && impT[j] > t) { impT[j + 1] = impT[j]; impS[j + 1] = impS[j]; j--; }
            impT[j + 1] = t;
            impS[j + 1] = s;
        }
    }

    /** Final positions (relative to the roll centre) and circumradius, all as fractions of S. */
    private static float[][] layout(int n) {
        switch (n) {
            case 1: return new float[][]{{0, 0, 0.235f}};
            case 2: return new float[][]{{-0.19f, 0, 0.155f}, {0.19f, 0, 0.155f}};
            case 3: return new float[][]{{-0.19f, -0.085f, 0.135f}, {0.19f, -0.085f, 0.135f}, {0, 0.135f, 0.135f}};
            case 4: return new float[][]{{-0.15f, -0.125f, 0.12f}, {0.15f, -0.125f, 0.12f},
                    {-0.15f, 0.125f, 0.12f}, {0.15f, 0.125f, 0.12f}};
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
                float size = n <= 6 ? 0.1f : 0.082f;
                float dy = n <= 6 ? 0.24f : 0.175f, dx = n <= 6 ? 0.205f : 0.175f;
                float[][] out = new float[n][];
                int k = 0;
                int rc = rows.length;
                int ri = 0;
                for (int r : rows) {
                    float y = (ri - (rc - 1) / 2f) * dy;
                    for (int j = 0; j < r && k < n; j++) {
                        float x = (j - (r - 1) / 2f) * dx;
                        out[k++] = new float[]{x, y, size};
                    }
                    ri++;
                }
                return out;
            }
        }
    }

    private static float bounceHeight(float t) {
        if (t <= 0) return 0;
        float start = 0;
        for (int k = 0; k < PHASE_D.length; k++) {
            if (t < start + PHASE_D[k]) {
                float s = (t - start) / PHASE_D[k];
                return PHASE_H[k] * 4 * s * (1 - s);
            }
            start += PHASE_D[k];
        }
        return 0;
    }

    private void stepAnims(float tRoll) {
        for (int i = 0; i < animCount; i++) {
            Anim an = anims[i];
            float t = Math.max(0, tRoll - an.delay);
            float pPos = Math.min(1, t / T_POS);
            float ePos = 1 - (float) Math.pow(1 - pPos, 3);
            an.h = bounceHeight(t) * an.heightScale;
            an.x = lerp(an.startX, an.fx, ePos);
            an.baseY = lerp(an.startY, an.fy, ePos);
            float sz = lerp(an.startSize, an.size, ePos);
            an.y = an.baseY - an.h * sz * 0.6f;
            an.sz = sz * (1 + 0.22f * an.h);

            float p = Math.min(1, t / T_SPIN);
            float e1 = 1 - (float) Math.pow(1 - p, 3);
            float e2 = 1 - (float) Math.pow(1 - p, 2);
            tq1.setAxisAngle(an.ax1[0], an.ax1[1], an.ax1[2], an.a1 * (1 - e1));
            tq2.setAxisAngle(an.ax2[0], an.ax2[1], an.ax2[2], an.a2 * (1 - e2));
            tq3.pow(an.delta, 1 - e1);
            an.q.mul(an.qFinal, tq1).mul(an.q, tq2).mul(an.q, tq3).normalize();
        }
    }

    private void reveal() {
        state = RESULT;
        revealT0 = now;
        stepAnims(revealDelay + 1);
        Anim main = anims[0];
        for (int i = 0; i < animCount; i++) if (!anims[i].dropped) { main = anims[i]; break; }
        if (result.crit) {
            sfx.critSuccess();
            flashA = 1;
            sparkColor = GOLD_HI;
            burst(main.x, main.y, main.size, 1f);
        } else if (result.fumble) {
            sfx.critFailure();
            redA = 1;
            fumbleShakeAt = now;
            sparkColor = EMBER;
            burst(main.x, main.y, main.size, 0.55f);
        } else {
            sfx.landed();
            if (result.dc > 0) verdictAt = now + 0.32f;
        }
    }

    private void drawRoll(Canvas c) {
        if (state == ROLLING) stepAnims(now - rollT0);
        float rt = state == RESULT ? now - revealT0 : 0;
        float styleMix = state == RESULT ? Math.min(1, rt / 0.35f) : 0;
        int style = result.crit ? 1 : result.fumble ? 2 : 0;

        if (animCount == 1) {
            Anim an = anims[0];
            drawMagicCircle(c, an.fx, an.fy, an.size * 1.3f, rollA * (0.5f + 0.5f * ringBoost), now * 0.4f + ringBoost * now);
        }
        for (int i = 0; i < animCount; i++) {
            Anim an = anims[i];
            float dropFade = an.dropped ? Math.min(1, rt / 0.45f) : 0;
            float alpha = rollA * (1 - 0.62f * dropFade);
            float sz = an.sz * (1 - 0.18f * dropFade);
            int st = an.dropped ? 0 : style;
            int glowC = st == 1 ? 0xFFFFD27A : st == 2 ? 0xFFC0201A : 0xFFB0303A;
            float glowA = 0.3f + (st != 0 ? 0.35f * styleMix : 0) + 0.15f * (1 - Math.min(1, an.h * 3));
            drawGlow(c, an.x, an.y, sz * 1.9f, glowC, glowA * alpha);
            drawShadow(c, an.x, an.baseY + an.size * 1.05f, sz * (1 - 0.3f * an.h), 0.5f * alpha * (1 - 0.5f * an.h));
            drawDie(c, an.poly, an.kind, an.q, an.x, an.y, sz, alpha, st, st == 0 ? 0 : styleMix);
        }
        if (state == RESULT) drawResultText(c, rt);
    }

    private void drawResultText(Canvas c, float rt) {
        float a = rollA;
        // breakdown
        if (!result.breakdown.isEmpty()) {
            float ba = Math.min(1, rt / 0.3f);
            text.setTextSize(fitSize(result.breakdown, S * 0.048f, S * 0.8f, text));
            text.setColor(withAlpha(IVORY, (int) (a * ba * 220)));
            c.drawText(result.breakdown, cx, S * 0.705f, text);
        }
        // total
        float pop = easeOutBack(Math.min(1, rt / 0.42f));
        float ts = S * 0.14f * (0.4f + 0.6f * pop);
        serif.setTextSize(ts);
        int col = result.crit ? GOLD_HI : result.fumble ? EMBER : IVORY;
        String total = String.valueOf(result.total);
        serif.setColor(withAlpha(0xFF000000, (int) (a * 160)));
        c.drawText(total, cx + S * 0.004f, S * 0.825f + S * 0.006f, serif);
        serif.setColor(withAlpha(col, (int) (a * 255 * Math.min(1, rt / 0.15f))));
        c.drawText(total, cx, S * 0.825f, serif);

        // verdict
        String v = null;
        int vc = GOLD;
        if (result.crit) { v = "КРИТИЧЕСКИЙ УСПЕХ"; vc = GOLD_HI; }
        else if (result.fumble) { v = "КРИТИЧЕСКИЙ ПРОВАЛ"; vc = CRIMSON; }
        else if (result.dc > 0) {
            boolean ok = roller.passed(result);
            v = (ok ? "УСПЕХ" : "ПРОВАЛ") + " · СЛ " + result.dc;
            vc = ok ? GOLD_HI : CRIMSON;
        } else if (result.mode != Roller.MODE_NORMAL) {
            v = result.mode == Roller.MODE_ADV ? "ПРЕИМУЩЕСТВО" : "ПОМЕХА";
            vc = MUTED;
        }
        if (v != null) {
            float delay = (result.dc > 0 && !result.crit && !result.fumble) ? 0.3f : 0.12f;
            float va = Math.max(0, Math.min(1, (rt - delay) / 0.3f));
            float sz = S * 0.042f;
            text.setTextSize(fitSize(v, sz, S * 0.6f, text));
            text.setLetterSpacing(0.08f * (2 - va));
            text.setColor(withAlpha(vc, (int) (a * va * 255)));
            c.drawText(v, cx, S * 0.905f + (1 - va) * S * 0.02f, text);
            text.setLetterSpacing(0);
            // decorative rules either side
            float w = Math.min(S * 0.6f, text.measureText(v)) / 2 + S * 0.03f;
            line.setStrokeWidth(S * 0.003f);
            line.setColor(withAlpha(vc, (int) (a * va * 160)));
            float ly = S * 0.893f;
            float len = S * 0.05f * va;
            c.drawLine(cx - w - len, ly, cx - w, ly, line);
            c.drawLine(cx + w, ly, cx + w + len, ly, line);
        }
    }

    // ------------------------------------------------------------------ 3D die

    /**
     * Draws a die: flat-shaded visible faces, gold edges, and numbers mapped onto the faces with a
     * perspective homography. style 0 normal, 1 crit gold, 2 fumble; mix blends from normal.
     */
    private void drawDie(Canvas c, Poly p, int kind, Quat q, float x, float y, float size, float alpha,
                         int style, float mix) {
        int nv = p.v.length;
        for (int i = 0; i < nv; i++) {
            q.rotate(p.v[i], pv);
            float k = CAM / (CAM - pv[2]);
            projX[i] = x + pv[0] * size * k;
            projY[i] = y - pv[1] * size * k;
        }
        int br = mixC(BASE[0][0], BASE[style][0], mix), bgc = mixC(BASE[0][1], BASE[style][1], mix),
                bb = mixC(BASE[0][2], BASE[style][2], mix);
        int er = mixC(EDGE[0][0], EDGE[style][0], mix), eg = mixC(EDGE[0][1], EDGE[style][1], mix),
                eb = mixC(EDGE[0][2], EDGE[style][2], mix);
        int nr = mixC(NUM[0][0], NUM[style][0], mix), ng = mixC(NUM[0][1], NUM[style][1], mix),
                nb = mixC(NUM[0][2], NUM[style][2], mix);
        int a = (int) (alpha * 255);
        edge.setStrokeWidth(Math.max(1.2f, size * 0.03f));
        edge.setColor(Color.argb(a, er, eg, eb));

        for (int fi = 0; fi < p.faces.length; fi++) {
            Poly.Face f = p.faces[fi];
            q.rotate(f.n, pv);
            q.rotate(f.c, pv2);
            float vis = CAM * pv[2] - (pv[0] * pv2[0] + pv[1] * pv2[1] + pv[2] * pv2[2]);
            if (vis <= 0) continue;
            float diff = Math.max(0, pv[0] * LIGHT[0] + pv[1] * LIGHT[1] + pv[2] * LIGHT[2]);
            float spec = (float) Math.pow(Math.max(0, pv[0] * HALF[0] + pv[1] * HALF[1] + pv[2] * HALF[2]), 28) * 0.6f;
            float sh = 0.25f + 0.9f * diff;
            int r = clamp255(br * sh + 255 * spec), g = clamp255(bgc * sh + 235 * spec), b = clamp255(bb * sh + 200 * spec);
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
            float minFacing = size < S * 0.11f ? 0.55f : 0.3f;
            if (facing < minFacing) continue;
            float ta = Math.min(1, (facing - minFacing) / 0.3f);
            float s = f.inr * p.textScale;
            for (int corner = 0; corner < 4; corner++) {
                float ux = src[corner * 2], uy = -src[corner * 2 + 1];  // text y-down → up vector
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
            label.setColor(Color.argb((int) (a * ta * 0.55f), 0, 0, 0));
            tmpPath.offset(size * 0.008f, size * 0.012f);
            c.drawPath(tmpPath, label);
            tmpPath.offset(-size * 0.008f, -size * 0.012f);
            label.setColor(Color.argb((int) (a * ta), nr, ng, nb));
            c.drawPath(tmpPath, label);
        }
    }

    private void drawGlow(Canvas c, float x, float y, float r, int color, float alpha) {
        if (alpha <= 0.01f) return;
        shaderM.setScale(r, r);
        shaderM.postTranslate(x, y);
        glowShader.setLocalMatrix(shaderM);
        glow.setColor(color);
        glow.setAlpha((int) (Math.min(1, alpha) * 255));
        c.drawCircle(x, y, r, glow);
    }

    private void drawShadow(Canvas c, float x, float y, float r, float alpha) {
        if (alpha <= 0.01f) return;
        shaderM.setScale(r * 1.1f, r * 0.28f);
        shaderM.postTranslate(x, y);
        shadowShader.setLocalMatrix(shaderM);
        shadow.setAlpha((int) (Math.min(1, alpha) * 255));
        rect.set(x - r * 1.1f, y - r * 0.28f, x + r * 1.1f, y + r * 0.28f);
        c.drawOval(rect, shadow);
    }

    // ------------------------------------------------------------------ sparks

    private void burst(float x, float y, float size, float power) {
        for (int i = 0; i < SPARKS; i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            float sp = S * (0.25f + rng.nextFloat() * 0.75f) * power;
            spX[i] = x + (float) Math.cos(ang) * size * 0.5f;
            spY[i] = y + (float) Math.sin(ang) * size * 0.5f;
            spVX[i] = (float) Math.cos(ang) * sp;
            spVY[i] = (float) Math.sin(ang) * sp - S * 0.15f * power;
            spMax[i] = spLife[i] = 0.6f + rng.nextFloat() * 0.9f;
        }
    }

    private void drawSparks(Canvas c, float dt) {
        fill.setShader(null);
        line.setStrokeWidth(S * 0.006f);
        for (int i = 0; i < SPARKS; i++) {
            if (spLife[i] <= 0) continue;
            spLife[i] -= dt;
            spVX[i] *= (float) Math.exp(-dt * 2.2);
            spVY[i] = spVY[i] * (float) Math.exp(-dt * 2.2) + S * 0.45f * dt;
            float ox = spX[i], oy = spY[i];
            spX[i] += spVX[i] * dt;
            spY[i] += spVY[i] * dt;
            float k = Math.max(0, spLife[i] / spMax[i]);
            int a = (int) (255 * k * rollA);
            line.setColor(withAlpha(sparkColor, a));
            c.drawLine(ox - (spX[i] - ox) * 1.5f, oy - (spY[i] - oy) * 1.5f, spX[i], spY[i], line);
            fill.setColor(withAlpha(0xFFFFFFFF, (int) (a * 0.8f)));
            c.drawCircle(spX[i], spY[i], S * 0.004f * (0.5f + k), fill);
        }
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
        fill.setColor(0xF2070405);
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
            int col = flags.contains("C") ? GOLD_HI : flags.contains("F") ? EMBER : IVORY;

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
            text.setColor(withAlpha(0xFF7A6A5A, a));
            c.drawText(subS, cx, y + S * 0.045f, text);

            line.setStrokeWidth(1);
            line.setColor(withAlpha(0xFF3A2A22, a));
            c.drawLine(cx - S * 0.25f, y + S * 0.08f, cx + S * 0.25f, y + S * 0.08f, line);
        }

        if (clearConfirm) {
            fill.setColor(0xE6000000);
            c.drawCircle(cx, cy, S * 0.5f, fill);
            rect.set(cx - S * 0.26f, cy - S * 0.07f, cx + S * 0.26f, cy + S * 0.07f);
            fill.setColor(0xFF6A1418);
            c.drawRoundRect(rect, S * 0.07f, S * 0.07f, fill);
            line.setStrokeWidth(S * 0.005f);
            line.setColor(CRIMSON);
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

    private void drawSettings(Canvas c) {
        int a = (int) (settingsA * 255);
        fill.setShader(null);
        fill.setColor(withAlpha(0xFF070405, (int) (a * 0.95f)));
        c.drawCircle(cx, cy, S * 0.75f, fill);
        text.setTextSize(S * 0.045f);
        text.setLetterSpacing(0.15f);
        text.setColor(withAlpha(GOLD, a));
        c.drawText("НАСТРОЙКИ", cx, S * 0.22f, text);
        text.setLetterSpacing(0);
        settingRow(c, S * 0.38f, "Звук", roller.sound, a);
        settingRow(c, S * 0.53f, "Встряхнуть — бросок", roller.shake, a);
        drawPill(c, cx, S * 0.73f, S * 0.36f, S * 0.1f, true, true, "Готово", S * 0.048f, a);
    }

    private void settingRow(Canvas c, float y, String name, boolean on, int a) {
        rect.set(cx - S * 0.36f, y - S * 0.055f, cx + S * 0.36f, y + S * 0.055f);
        fill.setColor(withAlpha(0xFF1C1012, a));
        c.drawRoundRect(rect, S * 0.055f, S * 0.055f, fill);
        line.setStrokeWidth(S * 0.004f);
        line.setColor(withAlpha(on ? GOLD : GOLD_DIM, a));
        c.drawRoundRect(rect, S * 0.055f, S * 0.055f, line);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(fitSize(name, S * 0.04f, S * 0.48f, text));
        text.setColor(withAlpha(IVORY, a));
        c.drawText(name, cx - S * 0.31f, y + S * 0.014f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(S * 0.04f);
        text.setColor(withAlpha(on ? GOLD_HI : MUTED, a));
        c.drawText(on ? "ВКЛ" : "ВЫКЛ", cx + S * 0.31f, y + S * 0.014f, text);
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
            if (Math.abs(y - S * 0.38f) < S * 0.07f) {
                roller.sound = !roller.sound;
                sfx.soundOn = roller.sound;
                sfx.select();
            } else if (Math.abs(y - S * 0.53f) < S * 0.07f) {
                roller.shake = !roller.shake;
                if (sensors != null && accel != null) {
                    if (roller.shake) sensors.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
                    else sensors.unregisterListener(this);
                }
                sfx.select();
            } else if (y > S * 0.64f) {
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
        if (state == ROLLING) return;
        if (state == RESULT) {
            if (hitRollArea(x, y)) startRoll();
            else {
                state = IDLE;
                idleSwapAt = now;
                sfx.tick();
            }
            return;
        }
        // IDLE
        if (Math.hypot(x - cx, y - cy) < S * 0.24f) {
            startRoll();
            return;
        }
        if (Math.abs(y - chipTopY()) < S * 0.08f && Math.abs(x - cx) < S * 0.2f) {
            if (active != Roller.P_DIE) select(Roller.P_DIE);
            else adjust(x < cx ? -1 : 1);
            return;
        }
        if (Math.hypot(x - leftChipX(), y - cy) < sideChipR() * 1.35f) {
            if (active != Roller.P_COUNT) select(Roller.P_COUNT);
            else adjust(y < cy ? 1 : -1);
            return;
        }
        if (Math.hypot(x - rightChipX(), y - cy) < sideChipR() * 1.35f) {
            if (active != Roller.P_MOD) select(Roller.P_MOD);
            else adjust(y < cy ? 1 : -1);
            return;
        }
        if (Math.abs(y - bottomY()) < S * 0.07f) {
            if (modeChipShown() && x > cx) {
                active = Roller.P_MODE;
                adjust(1);
                return;
            }
            if (Math.abs(x - dcChipX()) < S * 0.15f) {
                if (active != Roller.P_DC) select(Roller.P_DC);
                else adjust(x < dcChipX() ? -1 : 1);
            }
        }
    }

    private boolean hitRollArea(float x, float y) {
        return y < S * 0.68f && Math.hypot(x - cx, y - S * 0.40f) < S * 0.33f;
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

    /** Back button. Returns true when it was consumed. */
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

    private static int mixC(int a, int b, float t) { return (int) (a + (b - a) * t); }

    private static int clamp255(float v) { return (int) Math.max(0, Math.min(255, v)); }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }
}
