package com.fourdeviltears.app;

import android.util.Log;

import java.io.File;
import java.io.FileWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Touch for the game's menus, from the 2D sprites the game really draws in the current frame
 * (positions in 1280x720 game pixels, published by the renderer every frame). Nothing is tied
 * to fixed coordinates: rows, value arrows and button prompts are recognised by their shape,
 * so the hit areas follow the game's layout on every menu screen.
 *
 *  - list rows: a tap on the selected row confirms (A), a tap on another row moves the cursor
 *    there with D-pad presses (the second tap confirms);
 *  - the selected row's value (sliders, choices): its left / right arrows or the left / right
 *    half of the value act at once (Left / Right);
 *  - button prompts at the bottom (icon + label): act as that button.
 */
final class UiTouch {
    private static final String TAG = "4DevilTearsUi";
    static final int INTS = 11;
    /** Tap decisions are also appended here (files/ui_taps.txt) - logcat drops them. */
    static File sLogFile;

    /** What a tap does: press {@code button} {@code times} times; NONE - swallowed. */
    static final class Action {
        final int button, times;
        final String why;
        int from = -1, target = -1;  // list moves: selected row -> tapped row (screen order)
        Action(int button, int times, String why) { this.button = button; this.times = times; this.why = why; }
    }
    static final Action NONE = new Action(-1, 0, "none");

    private static final class S {
        int x0, y0, x1, y1, u0, v0, u1, v1, tex, color, draw;
        long seen;
        int w() { return x1 - x0; }
        int h() { return y1 - y0; }
        float cx() { return (x0 + x1) * 0.5f; }
        float cy() { return (y0 + y1) * 0.5f; }
        boolean in(float x, float y, float pad) {
            return x >= x0 - pad && x <= x1 + pad && y >= y0 - pad && y <= y1 + pad;
        }
        @Override public String toString() { return x0 + "," + y0 + "-" + x1 + "," + y1; }
    }

    private static final class Line {
        final List<S> parts = new ArrayList<>();
        final List<S> arrows = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        float cy() { return (y0 + y1) * 0.5f; }
        int left() { return parts.isEmpty() ? x0 : Collections.min(parts, (a, b) -> a.x0 - b.x0).x0; }
        void add(S s) {
            parts.add(s);
            x0 = Math.min(x0, s.x0); y0 = Math.min(y0, s.y0);
            x1 = Math.max(x1, s.x1); y1 = Math.max(y1, s.y1);
        }
    }

    private static final class Prompt {
        S icon;
        int button;
        float hx0, hx1, hy0, hy1;
    }

    private final int[] buf = new int[768 * INTS];
    private final List<S> sprites = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private final List<S> arrowMemory = new ArrayList<>();
    private final List<S> strip = new ArrayList<>();
    private S stripSelected;
    private Line selected;
    private int count = -1;

    /** Reads the sprites of the last frame and recognises the menu parts. */
    boolean refresh() {
        try { count = TouchGamepadView.nativeUiSprites(buf); }
        catch (UnsatisfiedLinkError e) { count = -1; }
        sprites.clear(); lines.clear(); prompts.clear(); selected = null;
        if (count <= 0) return false;
        for (int i = 0; i < count; i++) {
            int o = i * INTS;
            S s = new S();
            s.x0 = buf[o]; s.y0 = buf[o + 1]; s.x1 = buf[o + 2]; s.y1 = buf[o + 3];
            s.u0 = buf[o + 4]; s.v0 = buf[o + 5]; s.u1 = buf[o + 6]; s.v1 = buf[o + 7];
            s.tex = buf[o + 8]; s.color = buf[o + 9]; s.draw = buf[o + 10];
            if (s.w() < 6 || s.h() < 6) continue;
            if (s.w() >= 1100 && s.h() >= 500) continue;  // full-screen backgrounds / fades
            if (s.x1 < 0 || s.x0 > 1280 || s.y1 < 0 || s.y0 > 720) continue;
            sprites.add(s);
        }
        classify();
        return !lines.isEmpty() || !prompts.isEmpty();
    }

    /** Index of the selected row in screen order, -1 if not known. */
    int selectedIndex() { return selected == null ? -1 : lines.indexOf(selected); }

    /** Writes a note to the tap log. */
    void note(String text) {
        Log.i(TAG, text);
        if (sLogFile != null) {
            try (FileWriter w = new FileWriter(sLogFile, true)) {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + text + "\n");
            } catch (Exception ignored) { }
        }
    }

    /** The current frame shows a menu: list rows or button prompts. */
    // Rows are needed: button prompts alone also show up over cutscenes / result screens,
    // where a tap keeps its old meaning (skip / continue).
    boolean hasMenu() {
        int known = 0;
        for (Prompt p : prompts) if (p.button >= 0) known++;
        return lines.size() >= 2 || (lines.size() == 1 && !prompts.isEmpty()) || strip.size() >= 3
                || known >= 2;
    }

    private static boolean near(int v, int want) { return Math.abs(v - want) <= 48; }

    /**
     * The button of a prompt icon, by where it sits in the game's button atlases (texture
     * coordinates). Learned from the menus: options / pause atlas (row v~0): B, A, X, Y side by
     * side every 320, bumpers 40x32 further right; story theater atlas (row v~2818): B, A, LB, RB.
     */
    static int iconButton(S ic) {
        int u = ic.u0, v = ic.v0;
        boolean wide = ic.w() > ic.h() + 4;  // bumper / trigger icons are 40x32
        if (v <= 48) {
            if (wide) {
                if (near(u, 2690)) return TouchGamepadView.BTN_LB;
                if (near(u, 3010)) return TouchGamepadView.BTN_RB;
                return -1;
            }
            if (near(u, 2)) return TouchGamepadView.BTN_B;
            if (near(u, 322)) return TouchGamepadView.BTN_A;
            if (near(u, 642)) return TouchGamepadView.BTN_X;
            if (near(u, 962)) return TouchGamepadView.BTN_Y;
            return -1;
        }
        if (near(v, 2818)) {
            if (near(u, 1794)) return TouchGamepadView.BTN_B;
            if (near(u, 2114)) return TouchGamepadView.BTN_A;
            if (near(u, 2434)) return TouchGamepadView.BTN_LB;
            if (near(u, 2754)) return TouchGamepadView.BTN_RB;
        }
        return -1;
    }

    private static boolean square(S s, int lo, int hi) {
        float r = s.w() / (float) s.h();
        return s.w() >= lo && s.w() <= hi && s.h() >= lo && s.h() <= hi && r > 0.75f && r < 1.33f;
    }

    private void classify() {
        // Button prompts: square icons in the bottom band; the button is the icon's place in
        // the prompt atlas (icons side by side: B, A - seen in the options; X, Y assumed next).
        List<S> icons = new ArrayList<>();
        for (S s : sprites) if (s.y0 >= 580 && square(s, 26, 60)) icons.add(s);
        icons.sort((a, b) -> a.x0 - b.x0);
        for (int i = 0; i < icons.size(); i++) {
            S ic = icons.get(i);
            int btn = iconButton(ic);
            Prompt p = new Prompt();  // unknown icons are kept (logged when tapped)
            p.icon = ic; p.button = btn;
            p.hx0 = ic.x0 - 14; p.hy0 = ic.y0 - 14; p.hy1 = ic.y1 + 14;
            float right = ic.x1 + 14;
            for (S s : sprites) {  // its label: the text right of the icon on the same band
                if (s == ic || icons.contains(s)) continue;
                if (s.x0 >= ic.x1 - 12 && s.x0 <= ic.x1 + 24 && Math.abs(s.cy() - ic.cy()) < 16) {
                    right = Math.max(right, s.x1);
                }
            }
            if (i + 1 < icons.size()) right = Math.min(right, icons.get(i + 1).x0 - 6);
            p.hx1 = right;
            prompts.add(p);
        }
        // Text rows, highlight bars and value arrows above the prompt band.
        // The prompt band starts at the button icons (rows reach down to it).
        int band = 700;
        for (S ic : icons) band = Math.min(band, ic.y0 - 2);
        List<S> upper = new ArrayList<>(), text = new ArrayList<>();
        for (S s : sprites) {
            if (s.y1 > band || icons.contains(s)) continue;
            upper.add(s);
            if (s.h() >= 24 && s.h() <= 52 && s.w() >= 100) text.add(s);
        }
        int mh = 40;
        if (!text.isEmpty()) {
            List<Integer> hs = new ArrayList<>();
            for (S s : text) hs.add(s.h());
            Collections.sort(hs);
            mh = hs.get(hs.size() / 2);
        }
        // A highlight bar: taller than the text rows, behind one of them.
        List<S> bars = new ArrayList<>();
        for (S b : upper) {
            if (b.w() < 200 || b.h() < mh + 6 || b.h() > 90) continue;
            for (S t : text) {
                if (t != b && t.h() < b.h() && Math.abs(t.cy() - b.cy()) < 14
                        && Math.min(t.x1, b.x1) > Math.max(t.x0, b.x0)) { bars.add(b); break; }
            }
        }
        List<S> rows = new ArrayList<>(), arrows = new ArrayList<>();
        for (S s : text) if (!bars.contains(s)) rows.add(s);
        for (S s : upper) if (square(s, 16, 48)) arrows.add(s);
        rows.sort((a, b) -> Float.compare(a.cy(), b.cy()));
        for (S s : rows) {
            Line hit = null;
            for (Line l : lines) if (Math.abs(l.cy() - s.cy()) < 12) { hit = l; break; }
            if (hit == null) { hit = new Line(); lines.add(hit); }
            hit.add(s);
        }
        // Keep the list column: lines starting near the most common left edge.
        int median = 0;
        if (lines.size() >= 2) {
            List<Integer> lefts = new ArrayList<>();
            for (Line l : lines) lefts.add(l.left());
            Collections.sort(lefts);
            median = lefts.get(lefts.size() / 2);
            final int m = median;
            lines.removeIf(l -> Math.abs(l.left() - m) > 48);
        }
        lines.sort((a, b) -> Float.compare(a.cy(), b.cy()));
        // Value arrows: same texture as the row's text (not glyphs of other text).
        for (S a : arrows) {
            for (Line l : lines) {
                if (Math.abs(l.cy() - a.cy()) >= 18) continue;
                boolean same = false;
                for (S p : l.parts) if (p.tex == a.tex) { same = true; break; }
                if (same) { l.arrows.add(a); break; }
            }
        }
        // The selected row: under the highlight bar, else the row shifted right of the
        // column, else the only row, else the row showing value arrows.
        for (S b : bars) {
            Line best = null;
            float bd = 40;
            for (Line l : lines) {
                float d = Math.abs(l.cy() - b.cy());
                if (d < bd) { bd = d; best = l; }
            }
            if (best != null) { selected = best; break; }
        }
        if (selected == null && lines.size() >= 2) {
            for (Line l : lines) if (l.left() - median >= 10) { selected = l; break; }
        }
        if (selected == null && lines.size() == 1) selected = lines.get(0);
        if (selected == null) {
            for (Line l : lines) if (!l.arrows.isEmpty()) { selected = l; break; }
        }
        // Horizontal strips of equal tiles (save / load slots...): 3+ same-size sprites on one
        // line at a regular spacing; the selected tile is the one a bigger frame sits on.
        strip.clear();
        stripSelected = null;
        List<S> tiles = new ArrayList<>();
        for (S s : upper) if (s.w() >= 40 && s.h() >= 40 && s.w() <= 300 && s.h() <= 200) tiles.add(s);
        tiles.sort((a, b) -> a.x0 - b.x0);
        for (S seed : tiles) {
            List<S> row = new ArrayList<>();
            for (S t : tiles) {
                if (Math.abs(t.y0 - seed.y0) <= 4 && Math.abs(t.w() - seed.w()) <= 3
                        && Math.abs(t.h() - seed.h()) <= 3 && t.tex == seed.tex) {
                    boolean dup = false;
                    for (S r : row) if (Math.abs(r.x0 - t.x0) < 6) dup = true;
                    if (!dup) row.add(t);
                }
            }
            if (row.size() < 3 || row.size() <= strip.size()) continue;
            row.sort((a, b) -> a.x0 - b.x0);
            float step = row.get(1).x0 - row.get(0).x0;
            boolean regular = step >= seed.w() - 2;
            for (int i = 1; i < row.size() && regular; i++) {
                regular = Math.abs((row.get(i).x0 - row.get(i - 1).x0) - step) <= 6;
            }
            if (!regular) continue;
            boolean inLine = false;  // text rows are handled as lines
            for (Line l : lines) for (S p : l.parts) if (row.contains(p)) inLine = true;
            if (inLine) continue;
            strip.clear();
            strip.addAll(row);
        }
        if (!strip.isEmpty()) {
            for (S f : upper) {
                if (strip.contains(f)) continue;
                for (S t : strip) {
                    if (f.cx() > t.x0 && f.cx() < t.x1 && f.cy() > t.y0 && f.cy() < t.y1
                            && f.w() > t.w() && f.w() <= t.w() * 2 && f.h() >= t.h()) {
                        stripSelected = t;
                        break;
                    }
                }
                if (stripSelected != null) break;
            }
        }
        // Arrows blink: remember them per row for a moment.
        long now = System.currentTimeMillis();
        for (Line l : lines) {
            if (!l.arrows.isEmpty()) {
                arrowMemory.removeIf(m -> Math.abs(m.cy() - l.cy()) < 12);
                for (S a : l.arrows) { arrowMemory.add(a); a.seen = now; }
            }
        }
        arrowMemory.removeIf(m -> now - m.seen > 1500);
        if (selected != null && selected.arrows.isEmpty()) {
            for (S m : arrowMemory) if (Math.abs(m.cy() - selected.cy()) < 18) selected.arrows.add(m);
        }
    }

    /** What a tap at game coordinates (gx, gy) does; null - not a menu the sprites explain. */
    Action tap(float gx, float gy) {
        if (!hasMenu()) return null;
        for (Prompt p : prompts) {
            if (gx >= p.hx0 && gx <= p.hx1 && gy >= p.hy0 && gy <= p.hy1) {
                if (p.button < 0) {
                    return log(new Action(-1, 0, "prompt ? " + p.icon + " u" + p.icon.u0 + "," + p.icon.v0
                            + "-" + p.icon.u1 + "," + p.icon.v1), gx, gy);
                }
                return log(new Action(p.button, 1, "prompt " + p.icon + " u" + p.icon.u0), gx, gy);
            }
        }
        // Strip tiles: the selected one confirms, another one is reached with Left / Right.
        for (int i = 0; i < strip.size(); i++) {
            S t = strip.get(i);
            if (!t.in(gx, gy, 8)) continue;
            if (stripSelected == null) return log(NONE, gx, gy);
            int sel = strip.indexOf(stripSelected);
            if (i == sel) return log(new Action(TouchGamepadView.BTN_A, 1, "confirm tile " + i), gx, gy);
            int steps = i - sel;
            return log(new Action(steps > 0 ? TouchGamepadView.BTN_RIGHT : TouchGamepadView.BTN_LEFT,
                    Math.abs(steps), "tile " + sel + "->" + i), gx, gy);
        }
        // Arrows: small, so a generous area.
        for (Line l : lines) {
            for (S a : l.arrows) {
                if (a.in(gx, gy, 22)) {
                    float mid = l.arrows.size() >= 2
                            ? (l.arrows.get(0).cx() + l.arrows.get(l.arrows.size() - 1).cx()) * 0.5f
                            : (l.x0 + l.x1) * 0.5f;
                    return log(new Action(a.cx() < mid ? TouchGamepadView.BTN_LEFT
                            : TouchGamepadView.BTN_RIGHT, 1, "arrow " + a), gx, gy);
                }
            }
        }
        // Rows: half the spacing to the neighbours (at least the text height).
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            float up = i > 0 ? (l.cy() - lines.get(i - 1).cy()) * 0.5f : 32;
            float down = i + 1 < lines.size() ? (lines.get(i + 1).cy() - l.cy()) * 0.5f : 32;
            float half = (l.y1 - l.y0) * 0.5f;
            up = Math.max(half, Math.min(up, 32));
            down = Math.max(half, Math.min(down, 32));
            if (gy < l.cy() - up || gy > l.cy() + down) continue;
            int right = l.x1;
            for (S a : l.arrows) right = Math.max(right, a.x1);
            if (gx < l.x0 - 40 || gx > right + 40) continue;
            if (selected == null) return log(NONE, gx, gy);
            int sel = lines.indexOf(selected);
            if (l == selected) {
                // The value part of the selected row: left / right half change it at once.
                if (!l.arrows.isEmpty()) {
                    float lo = Collections.min(l.arrows, (a, b) -> a.x0 - b.x0).x0;
                    float hi = Collections.max(l.arrows, (a, b) -> a.x1 - b.x1).x1;
                    if (gx >= lo - 10 && gx <= hi + 10) {
                        return log(new Action(gx < (lo + hi) * 0.5f ? TouchGamepadView.BTN_LEFT
                                : TouchGamepadView.BTN_RIGHT, 1, "value"), gx, gy);
                    }
                }
                return log(new Action(TouchGamepadView.BTN_A, 1, "confirm row " + i), gx, gy);
            }
            int steps = i - sel;
            Action mv = new Action(steps > 0 ? TouchGamepadView.BTN_DOWN : TouchGamepadView.BTN_UP,
                    Math.abs(steps), "move " + sel + "->" + i);
            mv.from = sel;
            mv.target = i;
            return log(mv, gx, gy);
        }
        return log(NONE, gx, gy);
    }

    private Action log(Action a, float gx, float gy) {
        StringBuilder sb = new StringBuilder();
        sb.append("tap ").append((int) gx).append(',').append((int) gy).append(" -> ").append(a.why)
          .append(" btn ").append(a.button).append(" x").append(a.times)
          .append(" | lines ").append(lines.size()).append(" sel ")
          .append(selected == null ? -1 : lines.indexOf(selected)).append(" prompts ").append(prompts.size());
        for (Line l : lines) sb.append(" [").append(l.x0).append(',').append(l.y0).append('-')
                .append(l.x1).append(',').append(l.y1).append(l.arrows.isEmpty() ? "" : " <>").append(']');
        if (!strip.isEmpty()) sb.append(" strip ").append(strip.size()).append(" sel ")
                .append(stripSelected == null ? -1 : strip.indexOf(stripSelected));
        for (Prompt p : prompts) sb.append(" P").append(p.button).append('@').append(p.icon)
                .append(" u").append(p.icon.u0);
        sb.append(" | bottom");  // the prompt band, always (icons the rules may miss)
        for (S s : sprites) {
            if (s.y0 < 560) continue;
            sb.append(' ').append(s).append(" t").append(Integer.toHexString(s.tex))
              .append(" u").append(s.u0).append(',').append(s.v0);
        }
        if (a.button < 0) {  // what was drawn there, to learn the screens not recognised yet
            sb.append(" | sprites");
            int n = 0;
            for (S s : sprites) {
                if (n++ >= 80) break;
                sb.append(' ').append(s).append(" t").append(Integer.toHexString(s.tex))
                  .append(" u").append(s.u0).append(',').append(s.v0);
            }
        }
        Log.i(TAG, sb.toString());
        if (sLogFile != null) {
            try (FileWriter w = new FileWriter(sLogFile, true)) {
                w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                        .format(new java.util.Date()) + " " + sb + "\n");
            } catch (Exception ignored) { }
        }
        return a;
    }
}
