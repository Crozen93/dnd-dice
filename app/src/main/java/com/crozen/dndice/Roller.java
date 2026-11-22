package com.crozen.dndice;

import android.content.SharedPreferences;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** Roll settings, the dice maths, and the persisted history. */
final class Roller {

    static final int[] SIDES = {4, 6, 8, 10, 12, 20, 100};
    static final int MODE_NORMAL = 0, MODE_ADV = 1, MODE_DIS = 2;
    static final int MAX_COUNT = 10, MOD_MIN = -20, MOD_MAX = 20, DC_MIN = 5, DC_MAX = 30;
    static final int MAX_HISTORY = 40;

    private final SecureRandom rng = new SecureRandom();
    private final SharedPreferences prefs;

    int dieIndex = 5;     // d20
    int count = 1;
    int mod = 0;
    int mode = MODE_NORMAL;
    int dc = 0;           // 0 = no DC
    boolean sound = true, shake = true;
    int skin = 0;         // 0 pearl (as in BG3), 1 ruby
    final List<String> history = new ArrayList<>();

    /** One physical die on the table. For d100 a roll has two of them (tens + units). */
    static final class DieRoll {
        int kind;        // KIND_* below
        int value;       // face value shown (1..n, or 0..9 for percentile dice)
        boolean dropped; // the lower/higher d20 of advantage/disadvantage
    }

    static final int KIND_D4 = 0, KIND_D6 = 1, KIND_D8 = 2, KIND_D10 = 3, KIND_D12 = 4, KIND_D20 = 5,
            KIND_TENS = 6, KIND_UNITS = 7;

    static final class Result {
        final List<DieRoll> dice = new ArrayList<>();
        int sides, mode, mod, dc;
        int natural;     // kept d20 face for crit checks (0 if not a single-d20 roll)
        int sum;         // dice total before the modifier
        int total;
        boolean crit, fumble;
        String expr;
        String breakdown;
    }

    Roller(SharedPreferences prefs) {
        this.prefs = prefs;
        dieIndex = clamp(prefs.getInt("die", 5), 0, SIDES.length - 1);
        count = clamp(prefs.getInt("count", 1), 1, MAX_COUNT);
        mod = clamp(prefs.getInt("mod", 0), MOD_MIN, MOD_MAX);
        mode = clamp(prefs.getInt("mode", 0), 0, 2);
        dc = prefs.getInt("dc", 0);
        if (dc != 0) dc = clamp(dc, DC_MIN, DC_MAX);
        sound = prefs.getBoolean("sound", true);
        shake = prefs.getBoolean("shake", true);
        skin = clamp(prefs.getInt("skin", 0), 0, 1);
        String h = prefs.getString("history", "");
        if (h != null && !h.isEmpty()) {
            for (String line : h.split("\n")) if (!line.isEmpty()) history.add(line);
        }
    }

    int sides() { return SIDES[dieIndex]; }

    boolean modeAvailable() { return sides() == 20; }

    /** Count actually used (advantage, disadvantage and d100 always roll a single result). */
    int effectiveCount() {
        if (sides() == 100) return 1;
        if (modeAvailable() && mode != MODE_NORMAL) return 1;
        return count;
    }

    int effectiveMode() { return modeAvailable() ? mode : MODE_NORMAL; }

    static int kindFor(int sides) {
        switch (sides) {
            case 4: return KIND_D4;
            case 6: return KIND_D6;
            case 8: return KIND_D8;
            case 10: return KIND_D10;
            case 12: return KIND_D12;
            default: return KIND_D20;
        }
    }

    Result roll() {
        Result r = new Result();
        int s = sides();
        r.sides = s;
        r.mode = effectiveMode();
        r.mod = mod;
        r.dc = dc;
        int n = effectiveCount();
        if (s == 100) {
            DieRoll tens = new DieRoll(), units = new DieRoll();
            tens.kind = KIND_TENS;
            units.kind = KIND_UNITS;
            tens.value = rng.nextInt(10);
            units.value = rng.nextInt(10);
            r.dice.add(tens);
            r.dice.add(units);
            int v = tens.value * 10 + units.value;
            r.sum = v == 0 ? 100 : v;
            r.breakdown = (tens.value == 0 ? "00" : String.valueOf(tens.value * 10)) + " + " + units.value;
        } else if (r.mode != MODE_NORMAL) {
            DieRoll a = new DieRoll(), b = new DieRoll();
            a.kind = b.kind = KIND_D20;
            a.value = 1 + rng.nextInt(20);
            b.value = 1 + rng.nextInt(20);
            boolean keepA = r.mode == MODE_ADV ? a.value >= b.value : a.value <= b.value;
            (keepA ? b : a).dropped = true;
            r.dice.add(a);
            r.dice.add(b);
            r.natural = keepA ? a.value : b.value;
            r.sum = r.natural;
            r.breakdown = a.value + " / " + b.value;
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                DieRoll d = new DieRoll();
                d.kind = kindFor(s);
                d.value = 1 + rng.nextInt(s);
                r.dice.add(d);
                r.sum += d.value;
                if (i > 0) sb.append(" + ");
                sb.append(d.value);
            }
            if (s == 20 && n == 1) r.natural = r.dice.get(0).value;
            r.breakdown = n > 1 ? sb.toString() : "";
        }
        r.total = r.sum + mod;
        r.crit = r.natural == 20;
        r.fumble = r.natural == 1;
        if (mod != 0) {
            String m = (mod > 0 ? "+ " : "− ") + Math.abs(mod);
            r.breakdown = r.breakdown.isEmpty() ? r.sum + " " + m : r.breakdown + "  " + m;
        }
        r.expr = expression();
        addHistory(r);
        return r;
    }

    boolean passed(Result r) {
        // In D&D 5e a natural 20 on an attack always hits and a natural 1 always misses.
        if (r.crit) return true;
        if (r.fumble) return false;
        return r.total >= r.dc;
    }

    String expression() {
        int s = sides();
        StringBuilder sb = new StringBuilder();
        int n = effectiveCount();
        if (n > 1) sb.append(n);
        sb.append(s == 100 ? "d100" : "d" + s);
        if (mod != 0) sb.append(mod > 0 ? "+" : "−").append(Math.abs(mod));
        return sb.toString();
    }

    String modeLabel() {
        switch (effectiveMode()) {
            case MODE_ADV: return "ПРЕИМ.";
            case MODE_DIS: return "ПОМЕХА";
            default: return "ОБЫЧНЫЙ";
        }
    }

    // ------------------------------------------------------------------ history

    /** Line format: expr|total|flags|breakdown|dc|epochMillis; flags: C crit, F fumble, A adv, D dis, P pass, X fail */
    private void addHistory(Result r) {
        StringBuilder f = new StringBuilder();
        if (r.crit) f.append('C');
        if (r.fumble) f.append('F');
        if (r.mode == MODE_ADV) f.append('A');
        if (r.mode == MODE_DIS) f.append('D');
        if (r.dc > 0) f.append(passed(r) ? 'P' : 'X');
        String line = r.expr + "|" + r.total + "|" + f + "|" + r.breakdown.replace('|', ' ') + "|" + r.dc + "|" + System.currentTimeMillis();
        history.add(0, line);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        save();
    }

    void clearHistory() {
        history.clear();
        save();
    }

    void save() {
        StringBuilder sb = new StringBuilder();
        for (String s : history) sb.append(s).append('\n');
        prefs.edit()
                .putInt("die", dieIndex)
                .putInt("count", count)
                .putInt("mod", mod)
                .putInt("mode", mode)
                .putInt("dc", dc)
                .putBoolean("sound", sound)
                .putBoolean("shake", shake)
                .putInt("skin", skin)
                .putString("history", sb.toString())
                .apply();
    }

    // ------------------------------------------------------------------ editing

    /** Adjusts a parameter by delta steps. Returns true when something changed. */
    boolean adjust(int param, int delta) {
        switch (param) {
            case P_DIE: {
                int ni = Math.floorMod(dieIndex + delta, SIDES.length);
                if (ni == dieIndex) return false;
                dieIndex = ni;
                return true;
            }
            case P_COUNT: {
                int nc = clamp(count + delta, 1, MAX_COUNT);
                if (nc == count) return false;
                count = nc;
                return true;
            }
            case P_MOD: {
                int nm = clamp(mod + delta, MOD_MIN, MOD_MAX);
                if (nm == mod) return false;
                mod = nm;
                return true;
            }
            case P_DC: {
                // off → 5 → 6 … 30 ; going below 5 turns it off
                int nd;
                if (dc == 0) nd = delta > 0 ? 10 : 0;
                else {
                    nd = dc + delta;
                    if (nd < DC_MIN) nd = 0;
                    if (nd > DC_MAX) nd = DC_MAX;
                }
                if (nd == dc) return false;
                dc = nd;
                return true;
            }
            case P_MODE: {
                if (!modeAvailable()) return false;
                mode = Math.floorMod(mode + (delta > 0 ? 1 : -1), 3);
                return true;
            }
        }
        return false;
    }

    static final int P_DIE = 0, P_COUNT = 1, P_MOD = 2, P_DC = 3, P_MODE = 4;

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
