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
    /** Scroll arrows at the ends of the strip (same texture as its tiles); null - none. */
    private S stripLeft, stripRight;
    /** Big preview of the selected tile (save / load: the same picture enlarged); null - none. */
    private S stripPreview;
    /** Vertical stack of picture rows (one sprite per item, e.g. the mission result menu). */
    private final List<S> stack = new ArrayList<>();
    private S stackSelected;
    /** The last stack seen: its selection frame blinks, so it is kept for a moment. */
    private final List<S> stackMemory = new ArrayList<>();
    private S stackMemorySelected;
    private long stackSeenAt;
    /** Words side by side to choose from (save / load, yes / no): the selected one is white. */
    private final List<S> choice = new ArrayList<>();
    private int choiceSel = -1;
    private Line selected;
    private boolean selectedByBar, selectedByColor;
    private int count = -1;

    /** Reads the sprites of the last frame and recognises the menu parts. */
    boolean refresh() {
        try { count = TouchGamepadView.nativeUiSprites(buf); }
        catch (UnsatisfiedLinkError e) { count = -1; }
        sprites.clear(); lines.clear(); prompts.clear(); selected = null; selectedByBar = false; selectedByColor = false;
        choice.clear(); choiceSel = -1;
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

    /** Writes every sprite of the current frame to the tap log (screenshot jobs). */
    void dump(String why) {
        refresh();
        StringBuilder sb = new StringBuilder("dump ").append(why).append(" n ").append(sprites.size())
                .append(" | lines ").append(lines.size()).append(" prompts ").append(prompts.size())
                .append(" strip ").append(strip.size()).append(" stack ").append(stack.size())
                .append(" choice ").append(choice.size())
                .append(" | sprites");
        for (S s : sprites) {
            sb.append(' ').append(s).append(" t").append(Integer.toHexString(s.tex))
              .append(" u").append(s.u0).append(',').append(s.v0).append('-').append(s.u1).append(',').append(s.v1)
              .append(" c").append(Integer.toHexString(s.color)).append(" d").append(s.draw);
        }
        note(sb.toString());
    }

    /**
     * A list the player picks from right now: rows with a recognisable selection (highlight
     * bar or the selected row shifted out of the column), a picture-row stack or a tile strip
     * with its frame. Text pop-ups (tutorial hints) have rows but no selection.
     */
    boolean hasSelectableList() {
        if (sprites.isEmpty()) return false;
        if (lines.size() >= 2 && selected != null) {
            if (selectedByBar || selectedByColor) return true;
            for (Line l : lines) if (l != selected && Math.abs(l.left() - selected.left()) >= 10) return true;
        }
        return (stack.size() >= 2 && stackSelected != null) || (strip.size() >= 3 && stripSelected != null)
                || choice.size() >= 2;
    }

    /** Button prompts whose button is known. */
    int knownPrompts() {
        int n = 0;
        for (Prompt p : prompts) if (p.button >= 0) n++;
        return n;
    }

    /**
     * A tutorial page over the game: one panel the full screen height, centred and narrower
     * than the screen (about 900 x 720), with no list to pick from.
     */
    boolean hasTutorialPanel() {
        for (S s : sprites) {
            if (s.h() >= 680 && s.w() >= 700 && s.w() < 1100 && Math.abs(s.cx() - 640) < 60) {
                return !hasSelectableList();
            }
        }
        return false;
    }

    /**
     * Lowest body text line of a tutorial page (game y), from its white letters inside the
     * panel; the faded location name line at the bottom of every page (y ~631) is left out.
     */
    int tutorialTextBottom() {
        int bottom = 0;
        for (S s : sprites) {
            if (s.w() >= 30 || s.h() < 20 || s.h() > 34 || s.color != 0xFFFFFFFF) continue;
            if (s.x0 < 192 || s.x1 > 1088 || s.y0 >= 628) continue;
            bottom = Math.max(bottom, s.y1);
        }
        return bottom;
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
                || (stack.size() >= 2 && stackSelected != null) || known >= 2 || choice.size() >= 2;
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
            if (ic.w() < 36 || (!wide && ic.h() < 36)) return -1;  // glyphs of a font, not icons
            if (wide) {
                if (ic.w() >= 80 && near(u, 1282)) return TouchGamepadView.BTN_START;  // START 88x40
                if (ic.w() > 60) return -1;
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

    /** A button icon: about 40 px tall, square or a known wide one (bumpers, START). */
    private static boolean iconShape(S s) {
        if (s.h() < 30 || s.h() > 60 || s.w() < 36 || s.w() > 100) return false;
        return square(s, 30, 60) || iconButton(s) >= 0;
    }

    /**
     * A known button icon with its label drawn as one sprite of the same atlas right after it
     * (the prompt bar look, placed higher on some screens - the pause menu). Icons inside text
     * (move lists, help boxes) are followed by font glyphs instead.
     */
    private boolean labeledPrompt(S ic) {
        if (iconButton(ic) < 0) return false;
        for (S s : sprites) {
            if (s == ic || s.tex != ic.tex || s.w() < 100 || s.h() < 24 || s.h() > 44) continue;
            if (s.x0 >= ic.x1 - 12 && s.x0 <= ic.x1 + 24 && Math.abs(s.cy() - ic.cy()) < 16) return true;
        }
        return false;
    }

    /** A bumper icon beside a tab bar (a wide picture on the same line, item menu). */
    private boolean tabBumper(S ic) {
        int b = iconButton(ic);
        if (b != TouchGamepadView.BTN_LB && b != TouchGamepadView.BTN_RB) return false;
        for (S s : sprites) {
            if (s == ic || s.tex == ic.tex || s.w() < 150 || s.h() < 24 || s.h() > 48) continue;
            if (Math.abs(s.cy() - ic.cy()) < 20 && s.x0 - ic.x1 <= 40 && ic.x0 - s.x1 <= 40) return true;
        }
        return false;
    }

    private static boolean grey(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (c >>> 24) >= 0x80 && r == g && g == b && r <= 0xA0;
    }

    /** The colour all parts of a line share; 0 - mixed. */
    private static int lineColor(Line l) {
        int c = l.parts.get(0).color;
        for (S p : l.parts) if (p.color != c) return 0;
        return c;
    }

    private void classify() {
        // Button prompts: square icons in the bottom band; the button is the icon's place in
        // the prompt atlas (icons side by side: B, A - seen in the options; X, Y assumed next).
        // Icons are 40x40 (bumpers 40x32); smaller squares there are font glyphs.
        List<S> icons = new ArrayList<>();
        for (S s : sprites) if ((s.y0 >= 580 || labeledPrompt(s) || tabBumper(s)) && iconShape(s)) icons.add(s);
        // A prompt that is fading out stays drawn (transparent) under the next screen's icon
        // at the same place: of icons stacked on one spot keep the most opaque (vertex colour
        // alpha, D3DCOLOR ARGB - checked to be non-zero on some icon before trusting it).
        boolean alphaKnown = false;
        for (S s : icons) if ((s.color >>> 24) != 0) alphaKnown = true;
        if (alphaKnown) {
            List<S> drop = new ArrayList<>();
            for (S a : icons) {
                if ((a.color >>> 24) == 0) { drop.add(a); continue; }
                for (S b : icons) {
                    if (a != b && Math.abs(a.x0 - b.x0) <= 4 && Math.abs(a.y0 - b.y0) <= 4
                            && (b.color >>> 24) > (a.color >>> 24)) { drop.add(a); break; }
                }
            }
            icons.removeAll(drop);
        }
        {   // still stacked (same opacity): the one drawn last is on top
            List<S> drop = new ArrayList<>();
            for (S a : icons) {
                for (S b : icons) {
                    if (a != b && Math.abs(a.x0 - b.x0) <= 4 && Math.abs(a.y0 - b.y0) <= 4
                            && b.draw > a.draw) { drop.add(a); break; }
                }
            }
            icons.removeAll(drop);
        }
        // Prompts inside a help box above the bar (character select: LB RB : choose, A : accept)
        // - only where the bar offers A: a move list (no A) shows the same icons as inputs.
        boolean bottomA = false;
        for (S s : icons) if (iconButton(s) == TouchGamepadView.BTN_A) bottomA = true;
        if (bottomA) {
            for (S s : sprites) {
                if (s.y0 >= 580 || !iconShape(s) || iconButton(s) < 0) continue;
                boolean dup = false;
                for (S o : icons) if (Math.abs(o.x0 - s.x0) <= 4 && Math.abs(o.y0 - s.y0) <= 4) dup = true;
                if (!dup) icons.add(s);
            }
        }
        icons.sort((a, b) -> a.x0 - b.x0);
        for (int i = 0; i < icons.size(); i++) {
            S ic = icons.get(i);
            int btn = iconButton(ic);
            Prompt p = new Prompt();  // unknown icons are kept (logged when tapped)
            p.icon = ic; p.button = btn;
            p.hx0 = ic.x0 - 14; p.hy0 = ic.y0 - 14; p.hy1 = ic.y1 + 14;
            // Its label: the text right of the icon on the same band (one sprite or a run of
            // font glyphs).
            int reach = ic.x1;
            for (boolean grown = true; grown; ) {
                grown = false;
                for (S s : sprites) {
                    if (s == ic || icons.contains(s) || s.x1 <= reach) continue;
                    if (s.x0 >= ic.x1 - 12 && s.x0 <= reach + 24 && Math.abs(s.cy() - ic.cy()) < 16) {
                        reach = s.x1;
                        grown = true;
                    }
                }
            }
            float right = tabBumper(ic) ? ic.x1 + 14 : Math.max(ic.x1 + 14, reach);
            for (int j = i + 1; j < icons.size(); j++) {  // the next icon on the same band
                S nx = icons.get(j);
                if (Math.abs(nx.cy() - ic.cy()) < 20 && nx.x0 > ic.x0 + 4) {
                    right = Math.min(right, nx.x0 - 6);
                    break;
                }
            }
            p.hx1 = right;
            prompts.add(p);
        }
        // Text rows, highlight bars and value arrows above the prompt band.
        // The prompt band starts at the button icons (rows reach down to it).
        // Only recognised button icons set it: font glyphs of the prompt atlas look square too.
        int band = 700;
        for (Prompt p : prompts) {
            if (p.button >= 0 && (p.icon.y0 >= 580 || labeledPrompt(p.icon))) band = Math.min(band, p.icon.y0 - 2);
        }
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
        // The tab bar a bumper switches (item menu: LB / RB beside the tab names) is not a row.
        for (Prompt p : prompts) {
            if (!tabBumper(p.icon)) continue;
            final float bcy = p.icon.cy();
            lines.removeIf(l -> Math.abs(l.cy() - bcy) < 20);
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
            if (best != null) { selected = best; selectedByBar = true; break; }
        }
        if (selected == null && lines.size() >= 2) {
            for (Line l : lines) if (l.left() - median >= 10) { selected = l; break; }
        }
        if (selected == null && lines.size() >= 2) {
            // Selection by colour (skill lists): one white row, the others grey.
            Line white = null;
            int nWhite = 0;
            boolean othersGrey = true;
            for (Line l : lines) {
                int c = lineColor(l);
                if (c == 0xFFFFFFFF) { white = l; nWhite++; }
                else if (!grey(c)) othersGrey = false;
            }
            if (nWhite == 1 && othersGrey) { selected = white; selectedByColor = true; }
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
        stripLeft = stripRight = null;
        stripPreview = null;
        if (!strip.isEmpty()) {
            S first = strip.get(0), last = strip.get(strip.size() - 1);
            float scy = first.cy();
            for (S a : upper) {
                if (strip.contains(a) || !square(a, 16, 48) || a.tex != first.tex
                        || Math.abs(a.cy() - scy) > 20) continue;
                if (a.x1 <= first.x0 + 4 && a.x0 >= first.x0 - 160) stripLeft = a;
                if (a.x0 >= last.x1 - 4 && a.x1 <= last.x1 + 160) stripRight = a;
            }
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
        // Vertical stacks of picture rows: each item one sprite of the same texture and
        // height, left edges in one column, at a regular vertical step (boxes may overlap).
        // Only a stack with a selection frame counts (a bigger sprite around one item) -
        // static picture labels (score captions...) stack the same way but have none.
        stack.clear();
        stackSelected = null;
        List<S> cands = new ArrayList<>();
        for (S t : upper) if (t.h() >= 24 && t.h() <= 90 && t.w() >= 60) cands.add(t);
        cands.sort((a, b) -> Float.compare(a.cy(), b.cy()));
        for (S seed : cands) {
            List<S> col = new ArrayList<>();
            for (S t : cands) {
                // One column: a common left edge, or a common centre (centred items, other
                // languages - the words have other lengths).
                if (t.tex != seed.tex || Math.abs(t.h() - seed.h()) > 3
                        || (Math.abs(t.x0 - seed.x0) > 48 && Math.abs(t.cx() - seed.cx()) > 24)) continue;
                boolean dup = false;
                for (S r : col) if (Math.abs(r.cy() - t.cy()) < t.h() * 0.5f) dup = true;
                if (!dup) col.add(t);
            }
            if (col.size() < 2 || col.size() <= stack.size()) continue;
            col.sort((a, b) -> Float.compare(a.cy(), b.cy()));
            float step = col.get(1).cy() - col.get(0).cy();
            boolean regular = step >= seed.h() * 0.6f && step <= seed.h() * 2.5f;
            for (int i = 1; i < col.size() && regular; i++) {
                regular = Math.abs((col.get(i).cy() - col.get(i - 1).cy()) - step) <= 6;
            }
            if (!regular) continue;
            S frame = null, inFrame = null;
            for (S f : sprites) {  // the frame may reach into the prompt band
                if (col.contains(f) || icons.contains(f)) continue;
                for (S t : col) {
                    if (f.cx() > t.x0 && f.cx() < t.x1 && f.cy() > t.y0 && f.cy() < t.y1
                            && f.w() > t.w() && f.w() <= t.w() * 2 && f.h() > t.h()
                            && f.h() <= t.h() * 2) { frame = f; inFrame = t; break; }
                }
                if (frame != null) break;
            }
            if (frame != null) {
                // The same glow drawn around every item is decoration ("new" badges in the
                // library), not the selection.
                int same = 0;
                for (S t : col) {
                    for (S f : sprites) {
                        if (f.tex == frame.tex && Math.abs(f.w() - frame.w()) <= 2 && Math.abs(f.h() - frame.h()) <= 2
                                && f.cx() > t.x0 && f.cx() < t.x1 && f.cy() > t.y0 && f.cy() < t.y1) { same++; break; }
                    }
                }
                if (same >= 2 && same >= col.size()) { frame = null; inFrame = null; }
            }
            if (frame == null && col.size() >= 3) {
                // No frame: the selected item is the one shifted right out of the column
                // (mission start menu: Старт миссии / Меню миссии / Энергия).
                List<Integer> xs = new ArrayList<>();
                for (S t : col) xs.add(t.x0);
                Collections.sort(xs);
                int med = xs.get(xs.size() / 2);
                S shifted = null;
                int n = 0;
                for (S t : col) if (t.x0 - med >= 10) { shifted = t; n++; }
                if (n == 1) { frame = shifted; inFrame = shifted; }
            }
            if (frame == null) continue;
            boolean asLines = true;  // already a text list (value arrows etc. handled there)
            for (S t : col) {
                boolean in = false;
                for (Line l : lines) if (l.parts.contains(t)) in = true;
                if (!in) asLines = false;
            }
            if (asLines) continue;
            stack.clear();
            stack.addAll(col);
            stackSelected = inFrame;
        }
        {
            long t = System.currentTimeMillis();
            if (stackSelected != null) {
                stackMemory.clear(); stackMemory.addAll(stack);
                stackMemorySelected = stackSelected; stackSeenAt = t;
            } else if (stack.isEmpty() && !stackMemory.isEmpty() && t - stackSeenAt < 700) {
                // Same items still drawn (texture and place) - only the frame blinked off.
                int found = 0;
                for (S m : stackMemory) {
                    for (S s : sprites) {
                        if (s.tex == m.tex && Math.abs(s.x0 - m.x0) <= 2 && Math.abs(s.y0 - m.y0) <= 2
                                && s.w() == m.w() && s.h() == m.h()) { found++; break; }
                    }
                }
                if (found == stackMemory.size()) {
                    stack.addAll(stackMemory);
                    stackSelected = stackMemorySelected;
                }
            }
        }
        // The selected tile's picture shown enlarged elsewhere (load screen: the save's preview
        // above the slots) - a tap on it confirms like the tile itself.
        if (stripSelected != null) {
            S tile = stripSelected;
            for (S thumb : upper) {
                if (thumb.tex == tile.tex || thumb.cx() < tile.x0 || thumb.cx() > tile.x1
                        || thumb.cy() < tile.y0 || thumb.cy() > tile.y1 || thumb.w() < 24) continue;
                for (S big : sprites) {
                    if (big.tex == thumb.tex && big != thumb && big.w() >= tile.w() * 2
                            && big.h() >= tile.h() * 2) { stripPreview = big; break; }
                }
                if (stripPreview != null) break;
            }
        }
        // Word choice (save / load, yes / no): font glyphs on one row, split into words by
        // colour - the selected word white, the others grey - or by a gap.
        {
            List<S> glyphs = new ArrayList<>();
            for (S g : sprites) if (g.w() < 30 && g.h() >= 20 && g.h() <= 34 && !icons.contains(g)) glyphs.add(g);
            glyphs.sort((a, b) -> a.y0 != b.y0 ? a.y0 - b.y0 : a.x0 - b.x0);
            List<S> done = new ArrayList<>();
            for (S seed : glyphs) {
                if (done.contains(seed) || !choice.isEmpty()) continue;
                List<S> row = new ArrayList<>();
                for (S g : glyphs) {
                    if (g.tex == seed.tex && Math.abs(g.y0 - seed.y0) <= 2 && Math.abs(g.y1 - seed.y1) <= 2) row.add(g);
                }
                done.addAll(row);
                row.sort((a, b) -> a.x0 - b.x0);
                List<S> words = new ArrayList<>();
                S cur = null;
                S prev = null;
                for (S g : row) {
                    if (cur == null || g.color != cur.color || g.x0 - prev.x1 > 12) {
                        cur = new S();
                        cur.x0 = g.x0; cur.y0 = g.y0; cur.x1 = g.x1; cur.y1 = g.y1;
                        cur.color = g.color; cur.tex = g.tex;
                        words.add(cur);
                    } else {
                        cur.x1 = Math.max(cur.x1, g.x1);
                        cur.y0 = Math.min(cur.y0, g.y0); cur.y1 = Math.max(cur.y1, g.y1);
                    }
                    prev = g;
                }
                if (words.size() < 2 || words.size() > 6) continue;
                int sel = -1, nWhite = 0;
                boolean ok = true;
                for (int i = 0; i < words.size(); i++) {
                    int c = words.get(i).color;
                    if (c == 0xFFFFFFFF) { sel = i; nWhite++; }
                    else if (!grey(c)) ok = false;
                }
                if (!ok || nWhite != 1) continue;
                choice.addAll(words);
                choiceSel = sel;
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
        // Word choice: the selected word confirms, another one is reached with Left / Right.
        // Neighbouring words split at the middle of the gap between them.
        for (int i = 0; i < choice.size(); i++) {
            S w = choice.get(i);
            float lo = i > 0 ? (choice.get(i - 1).x1 + w.x0) * 0.5f : w.x0 - 16;
            float hi = i + 1 < choice.size() ? (w.x1 + choice.get(i + 1).x0) * 0.5f : w.x1 + 16;
            if (gx < lo || gx > hi || gy < w.y0 - 18 || gy > w.y1 + 18) continue;
            if (i == choiceSel) return log(new Action(TouchGamepadView.BTN_A, 1, "confirm word " + i), gx, gy);
            int steps = i - choiceSel;
            return log(new Action(steps > 0 ? TouchGamepadView.BTN_RIGHT : TouchGamepadView.BTN_LEFT,
                    Math.abs(steps), "word " + choiceSel + "->" + i), gx, gy);
        }
        // The tab bar beside tab bumpers: its left part acts as LB, its right part as RB (only
        // the bumpers drawn - the game shows LB only when there is a tab on the left).
        {
            Prompt lb = null, rb = null;
            for (Prompt p : prompts) {
                if (!tabBumper(p.icon)) continue;
                if (p.button == TouchGamepadView.BTN_LB) lb = p; else rb = p;
            }
            Prompt any = lb != null ? lb : rb;
            if (any != null && Math.abs(gy - any.icon.cy()) < 24) {
                float x0 = lb != null ? lb.icon.x1 : Float.MAX_VALUE, x1 = rb != null ? rb.icon.x0 : -Float.MAX_VALUE;
                for (S t : sprites) {
                    if (t.w() < 150 || t.h() < 24 || t.h() > 48 || Math.abs(t.cy() - any.icon.cy()) >= 20) continue;
                    x0 = Math.min(x0, t.x0); x1 = Math.max(x1, t.x1);
                }
                if (gx >= x0 && gx <= x1) {
                    float mid = (x0 + x1) * 0.5f;
                    Prompt hit = gx < mid ? lb : rb;  // the current tab's own side: nothing
                    if (hit == null) return log(NONE, gx, gy);
                    return log(new Action(hit.button, 1, "tab " + (hit == lb ? "<" : ">")), gx, gy);
                }
            }
        }
        // Strip scroll arrows: move past the visible end so the list scrolls by one.
        if (!strip.isEmpty() && stripSelected != null) {
            int sel = strip.indexOf(stripSelected);
            if (stripLeft != null && stripLeft.in(gx, gy, 30)) {
                return log(new Action(TouchGamepadView.BTN_LEFT, sel + 1, "strip <"), gx, gy);
            }
            if (stripRight != null && stripRight.in(gx, gy, 30)) {
                return log(new Action(TouchGamepadView.BTN_RIGHT, strip.size() - sel, "strip >"), gx, gy);
            }
        }
        if (stripPreview != null && stripPreview.in(gx, gy, 4)) {
            return log(new Action(TouchGamepadView.BTN_A, 1, "confirm preview"), gx, gy);
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
        // Picture-row stack: the selected item confirms, another one is reached with
        // Up / Down (the next tap on it confirms). Zones split halfway between items.
        if (stackSelected != null) {
            int sel = stack.indexOf(stackSelected);
            for (int i = 0; i < stack.size(); i++) {
                S t = stack.get(i);
                float top = i > 0 ? (stack.get(i - 1).cy() + t.cy()) * 0.5f : t.y0 - 8;
                float bot = i + 1 < stack.size() ? (t.cy() + stack.get(i + 1).cy()) * 0.5f : t.y1 + 8;
                if (gy < top || gy > bot || gx < t.x0 - 40 || gx > t.x1 + 40) continue;
                if (i == sel) return log(new Action(TouchGamepadView.BTN_A, 1, "confirm item " + i), gx, gy);
                int steps = i - sel;
                Action mv = new Action(steps > 0 ? TouchGamepadView.BTN_DOWN : TouchGamepadView.BTN_UP,
                        Math.abs(steps), "item " + sel + "->" + i);
                return log(mv, gx, gy);
            }
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
        if (!stack.isEmpty()) sb.append(" stack ").append(stack.size()).append(" sel ")
                .append(stackSelected == null ? -1 : stack.indexOf(stackSelected));
        if (!choice.isEmpty()) sb.append(" choice ").append(choice.size()).append(" sel ").append(choiceSel);
        if (!strip.isEmpty()) sb.append(" strip ").append(strip.size()).append(" sel ")
                .append(stripSelected == null ? -1 : strip.indexOf(stripSelected))
                .append(stripLeft != null ? " <" : "").append(stripRight != null ? " >" : "");
        for (Prompt p : prompts) sb.append(" P").append(p.button).append('@').append(p.icon)
                .append(" u").append(p.icon.u0).append(" c").append(Integer.toHexString(p.icon.color));
        sb.append(" | bottom");  // the prompt band, always (icons the rules may miss)
        for (S s : sprites) {
            if (s.y0 < 560) continue;
            sb.append(' ').append(s).append(" t").append(Integer.toHexString(s.tex))
              .append(" u").append(s.u0).append(',').append(s.v0)
              .append(" c").append(Integer.toHexString(s.color));
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
