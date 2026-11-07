package dev.motions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class Draw {
    private Draw() {}

    public static TextRenderer tr() { return MinecraftClient.getInstance().textRenderer; }

    public static int a(int c, float al) {
        al = Math.max(0f, Math.min(1f, al));
        int na = Math.round(((c >>> 24) & 255) * al);
        return (na << 24) | (c & 0xFFFFFF);
    }

    public static int mix(int x, int y, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int out = 0;
        for (int s = 0; s <= 24; s += 8) {
            int cx = (x >>> s) & 255, cy = (y >>> s) & 255;
            out |= (Math.round(cx + (cy - cx) * t) & 255) << s;
        }
        return out;
    }

    public static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Filled rounded rectangle with no overlapping rects (safe for translucent colours). */
    public static void round(DrawContext c, int x, int y, int w, int h, int r, int col) {
        if (w <= 0 || h <= 0 || (col >>> 24) == 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { c.fill(x, y, x + w, y + h, col); return; }
        c.fill(x, y + r, x + w, y + h - r, col);
        c.fill(x + r, y, x + w - r, y + r, col);
        c.fill(x + r, y + h - r, x + w - r, y + h, col);
        int[] ins = insets(r);
        for (int i = 0; i < r; i++) {
            int inset = ins[i];
            c.fill(x + inset, y + i, x + r, y + i + 1, col);
            c.fill(x + w - r, y + i, x + w - inset, y + i + 1, col);
            c.fill(x + inset, y + h - 1 - i, x + r, y + h - i, col);
            c.fill(x + w - r, y + h - 1 - i, x + w - inset, y + h - i, col);
        }
    }

    private static final int[][] INSETS = new int[24][];

    /** Corner insets per radius, computed once (avoids sqrt/rounding on every draw). */
    private static int[] insets(int r) {
        int[] a = r < INSETS.length ? INSETS[r] : null;
        if (a == null) {
            a = new int[r];
            for (int i = 0; i < r; i++) {
                double dy = r - i - 0.5;
                a[i] = r - (int) Math.round(Math.sqrt(r * r - dy * dy));
            }
            if (r < INSETS.length) INSETS[r] = a;
        }
        return a;
    }

    public static void border(DrawContext c, int x, int y, int w, int h, int r, int fill, int border) {
        round(c, x, y, w, h, r, border);
        round(c, x + 1, y + 1, w - 2, h - 2, Math.max(0, r - 1), fill);
    }

    public static int width(String s) { return tr().getWidth(s); }

    public static void text(DrawContext c, String s, int x, int y, int col) {
        if ((col >>> 24) < 8) return;
        c.drawText(tr(), s, x, y, col, false);
    }

    public static void bold(DrawContext c, String s, int x, int y, int col) {
        if ((col >>> 24) < 8) return;
        c.drawText(tr(), Text.literal(s).formatted(Formatting.BOLD), x, y, col, false);
    }

    public static void centered(DrawContext c, String s, int cx, int y, int col, boolean bold) {
        int w = bold ? tr().getWidth(Text.literal(s).formatted(Formatting.BOLD)) : width(s);
        if (bold) bold(c, s, cx - w / 2, y, col); else text(c, s, cx - w / 2, y, col);
    }

    public static void item(DrawContext c, ItemStack s, float x, float y, float scale) {
        if (s == null || s.isEmpty()) return;
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(scale, scale);
        c.drawItem(s, 0, 0);
        m.popMatrix();
    }

    /** Draws an item centred on (cx, cy), rotated by rot radians and scaled. */
    public static void itemCentered(DrawContext c, ItemStack s, float cx, float cy, float scale, float rot) {
        if (s == null || s.isEmpty()) return;
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate(rot);
        m.scale(scale, scale);
        m.translate(-8f, -8f);
        c.drawItem(s, 0, 0);
        m.popMatrix();
    }

    /** Bold "bubble" lettering: bold text with a thick soft outline, used for titles. */
    public static void bubble(DrawContext c, String s, int x, int y, int fill, int outline) {
        if ((fill >>> 24) < 8) return;
        Text t = Text.literal(s).formatted(Formatting.BOLD);
        TextRenderer r = tr();
        if ((outline >>> 24) >= 8) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    if (dx != 0 || dy != 0) c.drawText(r, t, x + dx, y + dy, outline, false);
        }
        c.drawText(r, t, x, y, fill, false);
    }

    /** Shortens bold text with an ellipsis so it fits in maxW pixels. */
    public static String fitBold(String s, int maxW) {
        TextRenderer r = tr();
        if (r.getWidth(Text.literal(s).formatted(Formatting.BOLD)) <= maxW) return s;
        String b = s;
        while (b.length() > 1 && r.getWidth(Text.literal(b + "\u2026").formatted(Formatting.BOLD)) > maxW)
            b = b.substring(0, b.length() - 1);
        return b + "\u2026";
    }
}
