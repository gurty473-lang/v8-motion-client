package dev.motions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;

public final class AuctionHud {
    private AuctionHud() {}

    public static final int W = 232, H = 78, MARGIN = 8;

    /** Top-left pixel of the card for the current layout settings (scaled GUI coordinates). */
    public static int posX(int sw, float sc) {
        float range = Math.max(0f, sw - W * sc - 2 * MARGIN);
        return Math.round(MARGIN + MotionsConfig.get().hudX * range);
    }

    public static int posY(int sh, float sc) {
        float range = Math.max(0f, sh - H * sc - 2 * MARGIN);
        return Math.round(MARGIN + MotionsConfig.get().hudY * range);
    }
    private static final Anim in = new Anim(0), flash = new Anim(0);
    private static int lastSec = -1;
    private static boolean shown;
    // cached card text, rebuilt only when something actually changes
    private static AuctionManager.State cState;
    private static long cBid = Long.MIN_VALUE;
    private static int cSec = -1;
    private static String cBidder;
    private static ItemStack cStack;
    private static String cTitle = "", cInfo = "", cMin = "", cTimer = "";
    private static int titleCol; // 0 = default title colour (set to the accent for the winner)

    // little green "+500M" popups that fade out quickly
    private record Pop(String text, long born, int slot) {}
    private static final ArrayList<Pop> pops = new ArrayList<>();
    private static final long POP_MS = 1700;

    public static void pushPayment(String text) {
        long now = System.currentTimeMillis();
        int alive = 0;
        for (Pop p : pops) if (now - p.born() < POP_MS) alive++;
        if (pops.size() >= 8) pops.remove(0);
        pops.add(new Pop(text, now, alive % 3));
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    public static void render(DrawContext c, RenderTickCounter tc) {
        MotionsConfig cfg = MotionsConfig.get();
        AuctionManager.State s = AuctionManager.state;
        // idle fast path: nothing to draw, so do no work at all
        if (!shown && (s == AuctionManager.State.IDLE || !cfg.showAuctionHud)) return;
        if (cfg.showAuctionHud && s != AuctionManager.State.IDLE) in.snap(1f); else in.to(0f);
        float vis = in.get();
        if (vis < 0.01f) { shown = false; return; }
        shown = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || mc.currentScreen instanceof MotionsScreen) return;

        long now = System.currentTimeMillis();
        long total = AuctionManager.durationSec * 1000L;
        long rem = s == AuctionManager.State.ACTIVE ? Math.max(0, AuctionManager.endMs - now) : 0;
        int sec = (int) ((rem + 999) / 1000);
        if (sec != lastSec) { lastSec = sec; flash.snap(1f); }
        flash.to(0f);

        // pop-out: the card drops in from the top with a small bounce when an auction starts
        float pop = 1f;
        if (s == AuctionManager.State.ACTIVE && cfg.animations) {
            float t = Math.min(1f, (now - AuctionManager.startMs) / (650f / cfg.animSpeed));
            pop = easeOutBack(t);
        }
        float slide = pop * vis;
        float alpha = Math.max(0f, Math.min(1f, slide * 1.6f));
        if (alpha < 0.01f) return;

        long since = now - AuctionManager.bidPulseMs;
        float glow = since < 1200 ? 1f - since / 1200f : 0f;
        float prog = total > 0 ? (float) rem / total : 0f;
        if (s == AuctionManager.State.ENDED)
            prog = Math.max(0f, 1f - (now - AuctionManager.endedAtMs) / (float) AuctionManager.WINNER_SHOW_MS);

        if (s != cState || AuctionManager.currentBid != cBid || sec != cSec || AuctionManager.stack != cStack
                || AuctionManager.currentBidder != cBidder) {
            cState = s; cBid = AuctionManager.currentBid; cSec = sec; cStack = AuctionManager.stack; cBidder = AuctionManager.currentBidder;
            cTimer = AuctionManager.time(sec);
            cMin = "Min " + AuctionManager.fmt(AuctionManager.minPrice);
            String item = cStack.getName().getString();
            if (s == AuctionManager.State.ACTIVE) {
                cTitle = cBid < 0 ? "No bids" : "Paid " + AuctionManager.fmt(cBid);
                cInfo = cBidder != null ? cBidder + " \u2022 paid you" : item + " \u2022 live";
            } else {
                cTimer = "ENDED";
                if (cBid < 0) {
                    cTitle = "No winner";
                    cInfo = "Auction ended \u2022 no payments";
                } else {
                    cTitle = "Winner: " + (cBidder != null ? cBidder : "unknown");
                    cMin = "Paid " + AuctionManager.fmt(cBid);
                    cInfo = "Auction ended \u2022 " + item;
                }
            }
            cTitle = Draw.fitBold(cTitle, 104);
        }
        float sc = cfg.hudScale;
        int sw = c.getScaledWindowWidth(), sh = c.getScaledWindowHeight();
        int x = posX(sw, sc), y0 = posY(sh, sc);
        boolean top = y0 + H * sc / 2f < sh / 2f; // slide in from the nearest screen edge
        float off = (1f - slide) * (H * sc + 24f) * (top ? -1f : 1f);
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(x, y0 + off);
        m.scale(sc, sc);
        titleCol = s == AuctionManager.State.ENDED && cBid >= 0 ? Draw.mix(cfg.accent, 0xFFFFFFFF, 0.25f) : 0;
        drawCard(c, 0, 0, W, cStack, cTitle, cMin,
                cTimer, cInfo, prog, alpha, cfg.accent, glow, flash.get(), (now % 1_000_000L) / 1000f);
        titleCol = 0;
        m.popMatrix();

        // payment popups: small green text that drifts away from the card and fades quickly
        if (!pops.isEmpty()) {
            float ps = sc * 0.85f;
            float cxm = x + W * sc / 2f;
            for (int i = pops.size() - 1; i >= 0; i--) {
                Pop p = pops.get(i);
                long age = now - p.born();
                if (age >= POP_MS) { pops.remove(i); continue; }
                float t = age / (float) POP_MS;
                float a = Math.min(1f, age / 100f) * (1f - t * t) * alpha;
                float drift = cfg.animations ? t * 12f : 0f;
                float py = top ? y0 + H * sc + 3f + p.slot() * 9f * ps + drift
                               : y0 - 11f * ps - 3f - p.slot() * 9f * ps - drift;
                m.pushMatrix();
                m.translate(cxm, py);
                m.scale(ps, ps);
                Draw.centered(c, p.text(), 0, 0, Draw.a(0xFF4ADE80, a), true);
                m.popMatrix();
            }
        }
    }

    /** Static card (used for the preview in the menu). */
    public static void drawCard(DrawContext c, int x, int y, int w, ItemStack st, String title, String min,
                                String timer, String info, float prog, float alpha, int ac, float glow, float flash) {
        drawCard(c, x, y, w, st, title, min, timer, info, prog, alpha, ac, glow, flash, -1f);
    }

    /** time &lt; 0 = static item; otherwise seconds, used to animate the floating item. */
    public static void drawCard(DrawContext c, int x, int y, int w, ItemStack st, String title, String min,
                                String timer, String info, float prog, float alpha, int ac, float glow, float flash,
                                float time) {
        if (alpha <= 0.01f) return;
        final int TXT = 0xFFF1F3F6, MUTED = 0xFF8B94A0;
        if (glow > 0.01f) Draw.round(c, x - 3, y - 3, w + 6, H + 6, 11, Draw.a(ac, 0.30f * glow * alpha));
        Draw.border(c, x, y, w, H, 9, Draw.a(0xFF0E1013, alpha), Draw.a(ac, 0.9f * alpha));
        Draw.round(c, x + 8, y + 8, 38, 38, 8, Draw.a(0xFF191C21, alpha));
        if (alpha > 0.5f && !st.isEmpty()) {
            float cx = x + 27, cy = y + 27;
            if (time < 0 || !MotionsConfig.get().animations) {
                Draw.item(c, st, x + 13, y + 13, 1.7f);
            } else {
                // soft pulsing glow behind the item
                float pulseGlow = 0.07f + 0.05f * (float) Math.sin(time * 3.0f);
                Draw.round(c, x + 8, y + 8, 38, 38, 8, Draw.a(ac, (pulseGlow + 0.18f * glow) * alpha));
                // float, sway and breathe; do one full spin when a bid arrives or the auction starts
                float bob = (float) Math.sin(time * 2.4f) * 2.2f;
                float tilt = (float) Math.sin(time * 1.6f) * 0.14f;
                float spin = glow > 0f ? (1f - glow * glow) * 6.2832f : 0f;
                float lift = -glow * 4f;
                float scale = 1.6f + 0.06f * (float) Math.sin(time * 3.2f) + 0.25f * glow;
                Draw.itemCentered(c, st, cx, cy + bob + lift, scale, tilt + spin);
                // twinkling sparkles orbiting the item
                int sparkle = Draw.mix(ac, 0xFFFFFFFF, 0.55f);
                for (int i = 0; i < 4; i++) {
                    float ang = time * 0.9f + i * 1.5708f;
                    float r = 21f + 2f * (float) Math.sin(time * 2f + i);
                    int sx = Math.round(cx + (float) Math.cos(ang) * r);
                    int sy = Math.round(cy + (float) Math.sin(ang) * r * 0.9f);
                    float tw = 0.45f + 0.55f * (float) Math.sin(time * 3.4f + i * 1.7f);
                    int col = Draw.a(sparkle, Math.max(0f, tw) * alpha);
                    c.fill(sx - 1, sy, sx + 2, sy + 1, col);
                    c.fill(sx, sy - 1, sx + 1, sy + 2, col);
                }
            }
        }

        Draw.bold(c, title, x + 54, y + 11, Draw.a(titleCol != 0 ? titleCol : TXT, alpha));
        int mw = Draw.width(min) + 14;
        Draw.border(c, x + 54, y + 25, mw, 14, 7, Draw.a(0xFF15181C, alpha), Draw.a(0xFF2A2F36, alpha));
        Draw.text(c, min, x + 61, y + 28, Draw.a(0xFFC9CFD6, alpha));

        int tw = 54, tx = x + w - tw - 10;
        Draw.border(c, tx, y + 13, tw, 22, 11, Draw.a(0xFF0E1013, alpha),
                Draw.a(Draw.mix(0xFF3A4048, ac, flash), alpha));
        Draw.centered(c, timer, tx + tw / 2, y + 20, Draw.a(TXT, alpha), true);

        Draw.round(c, x + 10, y + 53, 4, 4, 2, Draw.a(ac, alpha));
        String shown = Draw.tr().trimToWidth(info, w - 30);
        Draw.text(c, shown, x + 19, y + 51, Draw.a(MUTED, alpha));

        Draw.round(c, x + 8, y + 63, w - 16, 4, 2, Draw.a(0xFF22262C, alpha));
        int fw = (int) ((w - 16) * Math.max(0f, Math.min(1f, prog)));
        if (fw >= 2) Draw.round(c, x + 8, y + 63, fw, 4, 2, Draw.a(ac, alpha));
        Draw.centered(c, "Motions Client", x + w / 2, y + 69, Draw.a(0xFF5B636E, alpha), false);
    }
}
