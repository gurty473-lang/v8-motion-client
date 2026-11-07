package dev.motions;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.Click;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.*;

public class MotionsScreen extends Screen {
    // ---- theme ----
    static final int BG = 0xFF0D0F12, PANEL = 0xFF14171C, PANEL2 = 0xFF1A1E24, LINE = 0xFF272C34,
            TXT = 0xFFF1F3F6, MUTED = 0xFF8B94A0, DANGER = 0xFFF87171;
    static final int SB = 116;
    static final int[] DUR = {20, 30, 40, 60, 300, 1800, 3600, 86400};
    static final String[] DUR_L = {"20s", "30s", "40s", "1m", "5m", "30m", "1h", "24h"};
    static final String[] CHIPS = {"1M", "10M", "100M", "1B"};
    static final String[] P_NAMES = {"Spawner", "Elytra", "Lootdrop", "Mapart", "Ban", "Settings"};
    static final Set<String> BAN = Set.of("barrier", "bedrock", "structure_block", "structure_void", "jigsaw",
            "debug_stick", "spawner", "light", "reinforced_deepslate", "end_portal_frame", "knowledge_book");

    // ---- item list ----
    static final class Entry {
        Item item; ItemStack stack; String lc, id;
    }

    static List<Entry> ALL;

    static List<Entry> all() {
        if (ALL == null) {
            List<Entry> l = new ArrayList<>();
            for (Item it : Registries.ITEM) {
                if (it == Items.AIR) continue;
                Entry e = new Entry();
                e.item = it;
                e.stack = new ItemStack(it);
                e.lc = it.getName().getString().toLowerCase(Locale.ROOT);
                e.id = Registries.ITEM.getId(it).getPath();
                l.add(e);
            }
            l.sort(Comparator.comparing((Entry e) -> e.lc).thenComparing(e -> e.id)); // A-Z
            ALL = l;
        }
        return ALL;
    }

    record Hit(String id, int x, int y, int w, int h) {
        boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }

    // ---- state ----
    final Anim open = new Anim(0), tabHi = new Anim(0), page = new Anim(0), popup = new Anim(0),
            gridFade = new Anim(1), scroll = new Anim(0), durHi = new Anim(0), pHi = new Anim(2), statusA = new Anim(0);
    final Map<String, Anim> anims = new HashMap<>();
    final Map<Entry, Anim> itemHover = new HashMap<>();
    final List<Hit> hits = new ArrayList<>();
    List<Entry> shown = new ArrayList<>();

    boolean closing, popupOpen, dragSlider;
    int tab = 0, pTab = 2;
    float scrollTarget, scrollCur, maxScroll;
    long popupCloseAt, statusAt;
    String status = "";
    int statusColor = DANGER;
    TextFieldWidget price, search;

    int wx, wy, ww, wh, oy;
    int px, py, pw, ph;
    int gx, gy, gw, gh, cols = 1, cell = 26;
    int sliderX, sliderW = 96;
    Entry hoverEntry;
    boolean dragMove, dragScale, snapX, snapY;
    float grabX, grabY, miniK = 0.3f;
    int miniX, miniY, miniW, miniH, scaleX, scaleW = 100;
    static ItemStack SAMPLE;
    final Set<String> hoverOn = new HashSet<>();
    int lastHoverIdx = -1;

    public MotionsScreen() { super(Text.literal("Motions Client")); }

    @Override
    protected void init() {
        price = new TextFieldWidget(textRenderer, 0, 0, 100, 10, Text.literal("price"));
        price.setDrawsBackground(false);
        price.setMaxLength(16);
        price.setTextPredicate(s -> s.matches("[0-9.,kKmMbBtT]*"));
        price.setText(AuctionManager.draftPrice);
        price.setChangedListener(s -> AuctionManager.draftPrice = s);

        search = new TextFieldWidget(textRenderer, 0, 0, 100, 10, Text.literal("search"));
        search.setDrawsBackground(false);
        search.setMaxLength(32);
        search.setChangedListener(s -> refilter());

        open.to(1);
        page.to(1);
        refilter();
    }

    @Override public boolean shouldPause() { return false; }

    @Override
    public void close() {
        if (!closing) { closing = true; open.to(0); }
    }

    @Override
    public void removed() { MotionsConfig.save(); }

    // ---- filtering ----
    boolean matchTab(Entry e) {
        String p = e.id;
        return switch (pTab) {
            case 0 -> p.contains("spawner") || p.endsWith("spawn_egg");
            case 1 -> p.equals("elytra") || p.equals("firework_rocket") || p.equals("phantom_membrane");
            case 3 -> p.equals("map") || p.equals("filled_map") || p.contains("item_frame")
                    || p.endsWith("_wool") || p.endsWith("_carpet") || p.endsWith("_concrete")
                    || p.endsWith("_concrete_powder") || p.endsWith("_terracotta");
            case 4 -> p.contains("command_block") || BAN.contains(p);
            default -> true;
        };
    }

    void refilter() {
        String q = search == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (!matchTab(e)) continue;
            if (!q.isEmpty() && !e.lc.contains(q) && !e.id.contains(q)) continue;
            out.add(e);
        }
        shown = out;
        scrollTarget = 0;
        scroll.snap(0);
        gridFade.snap(0);
        gridFade.to(1);
    }

    // ---- helpers ----
    float hov(String id, boolean on) {
        Anim a = anims.computeIfAbsent(id, k -> new Anim(0));
        a.to(on ? 1f : 0f);
        if (on) { if (hoverOn.add(id)) Sfx.hover(); } else hoverOn.remove(id);
        return a.get();
    }

    /** Bold bubble-style heading: light fill with a thick darker accent outline. */
    void title(DrawContext c, String s, int x, int y, float a, int ac) {
        Draw.bubble(c, s, x, y, Draw.a(Draw.mix(0xFFFFFFFF, ac, 0.12f), a), Draw.a(Draw.mix(ac, 0xFF000000, 0.55f), a * 0.9f));
    }

    void hitSound(String id) { if (!id.startsWith("tg:")) Sfx.click(); }

    void hit(String id, int x, int y, int w, int h) { hits.add(new Hit(id, x, y, w, h)); }

    void setStatus(String s, int col) { status = s; statusColor = col; statusAt = System.currentTimeMillis(); statusA.snap(1); }

    // ---- main render ----
    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        hoverEntry = null;
        float o = open.get();
        if (closing && o <= 0.02f) { client.setScreen(null); return; }
        MotionsConfig cfg = MotionsConfig.get();
        int ac = cfg.accent;

        popup.to(popupOpen ? 1f : 0f);
        float pv = popup.get();
        if (popupOpen && popupCloseAt > 0 && System.currentTimeMillis() >= popupCloseAt) { popupCloseAt = 0; closePopup(); }
        boolean live = !popupOpen;

        ww = Math.min(540, width - 16);
        wh = Math.min(316, height - 16);
        oy = Math.round((1f - o) * 14);
        wx = (width - ww) / 2;
        wy = (height - wh) / 2 + oy;

        c.fill(0, 0, width, height, Draw.a(0xB0000000, o));
        Draw.border(c, wx, wy, ww, wh, 8, Draw.a(BG, o), Draw.a(LINE, o));

        sidebar(c, mx, my, o, ac, live);

        page.to(1);
        float pg = page.get();
        float pa = o * pg;
        int ty = wy + 14 + Math.round((1f - pg) * 10);
        if (tab == 0) auctionPage(c, mx, my, pa, ac, live, ty);
        else if (tab == 1) layoutPage(c, mx, my, pa, ac, live, ty);
        else settingsPage(c, mx, my, pa, ac, live, ty);

        if (pv > 0.01f) popupLayer(c, mx, my, delta, ac, pv);
        else { search.setFocused(false); }
        if (hoverEntry != null && pv > 0.95f) c.drawItemTooltip(textRenderer, hoverEntry.stack, mx, my);
    }

    // ---- sidebar ----
    void sidebar(DrawContext c, int mx, int my, float o, int ac, boolean live) {
        Draw.round(c, wx + 9, wy + 14, 9, 9, 3, Draw.a(ac, o));
        title(c, "Motions Client", wx + 22, wy + 14, o, ac);

        String[] names = TAB_NAMES;
        tabHi.to(tab);
        float th = tabHi.get();
        int y0 = wy + 42;
        Draw.border(c, wx + 8, y0 + Math.round(th * 28), SB - 16, 24, 6, Draw.a(PANEL2, o),
                Draw.a(Draw.mix(LINE, ac, 0.55f), o));
        for (int i = 0; i < 3; i++) {
            int y = y0 + i * 28;
            float hv = hov("tab" + i, live && Draw.in(mx, my, wx + 8, y, SB - 16, 24));
            if (live) hit("tab:" + i, wx + 8, y, SB - 16, 24);
            int col = Draw.mix(MUTED, TXT, Math.max(hv, i == tab ? 1f : 0f));
            if (o > 0.6f) Draw.item(c, tabStacks()[i], wx + 14, y + 6, 0.75f);
            Draw.text(c, names[i], wx + 32, y + 8, Draw.a(col, o));
        }
        int fy = wy + wh - 38;
        c.fill(wx + 10, fy - 6, wx + SB - 10, fy - 5, Draw.a(LINE, o));
        Draw.round(c, wx + 10, fy + 2, 20, 20, 5, Draw.a(PANEL2, o));
        String name = client.getSession().getUsername();
        Draw.text(c, textRenderer.trimToWidth(name, SB - 46), wx + 36, fy + 4, Draw.a(TXT, o));
        Draw.text(c, "Local profile", wx + 36, fy + 14, Draw.a(MUTED, o));
        c.fill(wx + SB, wy + 1, wx + SB + 1, wy + wh - 1, Draw.a(LINE, o));
    }

    // ---- auction page ----
    void auctionPage(DrawContext c, int mx, int my, float pa, int ac, boolean live, int ty) {
        int cx = wx + SB + 1, cw = ww - SB - 1;
        int lx = cx + 16, lw = Math.max(170, (cw - 48) / 2), rx = lx + lw + 16, rw = Math.min(AuctionHud.W, cx + cw - 16 - rx);

        title(c, "Create auction", lx, ty, pa, ac);
        Draw.text(c, "Pick an item, set a price and start.", lx, ty + 12, Draw.a(MUTED, pa));

        // item button
        int iy = ty + 30;
        float hv = hov("itembtn", live && Draw.in(mx, my, lx, iy, lw, 46));
        if (live) hit("item", lx, iy, lw, 46);
        Draw.border(c, lx, iy, lw, 46, 8, Draw.a(Draw.mix(PANEL, PANEL2, hv), pa), Draw.a(Draw.mix(LINE, ac, hv), pa));
        Draw.round(c, lx + 7, iy + 7, 32, 32, 6, Draw.a(BG, pa));
        if (AuctionManager.draftItem != null && pa > 0.5f) {
            float s = 1.5f + 0.15f * hv, size = 16 * s;
            Draw.item(c, AuctionManager.draftStack, lx + 7 + (32 - size) / 2f, iy + 7 + (32 - size) / 2f, s);
        }
        String it = AuctionManager.draftItem == null ? "Select item" : AuctionManager.draftStack.getName().getString();
        Draw.bold(c, textRenderer.trimToWidth(it, lw - 70), lx + 48, iy + 11, Draw.a(TXT, pa));
        Draw.text(c, "Click to browse all items", lx + 48, iy + 24, Draw.a(MUTED, pa));
        Draw.text(c, ">", lx + lw - 14, iy + 19, Draw.a(Draw.mix(MUTED, ac, hv), pa));

        // price
        int py2 = iy + 56;
        Draw.text(c, "Starting price", lx, py2, Draw.a(MUTED, pa));
        int fy = py2 + 11;
        boolean foc = price.isFocused();
        float fh = hov("pricebox", foc || (live && Draw.in(mx, my, lx, fy, lw, 22)));
        if (live) hit("pricebox", lx, fy, lw, 22);
        Draw.border(c, lx, fy, lw, 22, 6, Draw.a(PANEL, pa), Draw.a(Draw.mix(LINE, ac, fh), pa));
        price.setX(lx + 8); price.setY(fy + 7); price.setWidth(lw - 16); price.setHeight(10);
        if (pa > 0.3f) price.render(c, mx, my, 0);
        if (price.getText().isEmpty() && !foc) Draw.text(c, "e.g. 450M", lx + 8, fy + 7, Draw.a(MUTED, pa * 0.7f));

        int chipY = fy + 27, chipW = (lw - 12) / 4;
        for (int i = 0; i < 4; i++) {
            int x = lx + i * (chipW + 4);
            float ch = hov("chip" + i, live && Draw.in(mx, my, x, chipY, chipW, 16));
            if (live) hit("chip:" + i, x, chipY, chipW, 16);
            Draw.border(c, x, chipY, chipW, 16, 5, Draw.a(Draw.mix(PANEL, PANEL2, ch), pa), Draw.a(Draw.mix(LINE, ac, ch * 0.7f), pa));
            Draw.centered(c, CHIPS[i], x + chipW / 2, chipY + 4, Draw.a(Draw.mix(MUTED, TXT, ch), pa), false);
        }

        // duration
        int dy = chipY + 22;
        Draw.text(c, "Duration", lx, dy, Draw.a(MUTED, pa));
        int sy = dy + 11, sw = lw / DUR.length;
        int di = 0;
        for (int i = 0; i < DUR.length; i++) if (DUR[i] == AuctionManager.draftDuration) di = i;
        durHi.to(di);
        Draw.border(c, lx, sy, sw * DUR.length, 20, 6, Draw.a(PANEL, pa), Draw.a(LINE, pa));
        Draw.border(c, lx + Math.round(durHi.get() * sw) + 1, sy + 1, sw - 2, 18, 5,
                Draw.a(Draw.mix(PANEL, ac, 0.22f), pa), Draw.a(Draw.mix(LINE, ac, 0.7f), pa));
        for (int i = 0; i < DUR.length; i++) {
            int x = lx + i * sw;
            float dh = hov("dur" + i, live && Draw.in(mx, my, x, sy, sw, 20));
            if (live) hit("dur:" + i, x, sy, sw, 20);
            Draw.centered(c, DUR_L[i], x + sw / 2, sy + 6, Draw.a(Draw.mix(MUTED, TXT, Math.max(dh, i == di ? 1f : 0f)), pa), false);
        }

        // start / stop button
        int by = sy + 28;
        long pr = AuctionManager.parsePrice(price.getText());
        boolean running = AuctionManager.state == AuctionManager.State.ACTIVE;
        boolean valid = AuctionManager.draftItem != null && pr > 0;
        float bh = hov("start", live && Draw.in(mx, my, lx, by, lw, 24));
        if (live) hit("start", lx, by, lw, 24);
        int bcol = running ? Draw.mix(0xFF3A1D1D, DANGER, 0.35f * bh)
                : valid ? Draw.mix(ac, 0xFFFFFFFF, 0.18f * bh) : Draw.mix(PANEL2, LINE, bh);
        Draw.round(c, lx, by, lw, 24, 7, Draw.a(bcol, pa));
        String bt = running ? "Stop auction" : "Start auction";
        Draw.centered(c, bt, lx + lw / 2, by + 8, Draw.a(running ? 0xFFFFD0D0 : valid ? 0xFF0B0C0E : MUTED, pa), true);

        statusA.to(System.currentTimeMillis() - statusAt < 3500 ? 1f : 0f);
        Draw.text(c, status, lx, by + 29, Draw.a(statusColor, pa * statusA.get()));

        // preview + info
        if (rw >= 150) {
            Draw.text(c, "Live preview", rx, iy, Draw.a(MUTED, pa));
            AuctionHud.drawCard(c, rx, iy + 12, rw, AuctionManager.draftStack,
                    AuctionManager.draftItem == null ? "No item" : "No bids",
                    "Min " + (pr > 0 ? AuctionManager.fmt(pr) : "-"),
                    AuctionManager.time(AuctionManager.draftDuration),
                    AuctionManager.draftItem == null ? "Choose an item" : "Ready to start", 1f, pa, ac, 0f, 0f);
            int iby = iy + 12 + AuctionHud.H + 10;
            Draw.border(c, rx, iby, rw, 62, 8, Draw.a(PANEL, pa), Draw.a(LINE, pa));
            String[][] rows = {
                    {"Item", AuctionManager.draftItem == null ? "-" : AuctionManager.draftStack.getName().getString()},
                    {"Start price", pr > 0 ? AuctionManager.fmt(pr) : "-"},
                    {"Duration", AuctionManager.durationLabel(AuctionManager.draftDuration)},
                    {"Mode", "Client-side only"}};
            for (int i = 0; i < 4; i++) {
                Draw.text(c, rows[i][0], rx + 8, iby + 7 + i * 13, Draw.a(MUTED, pa));
                String v = textRenderer.trimToWidth(rows[i][1], rw - 80);
                Draw.text(c, v, rx + rw - 8 - Draw.width(v), iby + 7 + i * 13, Draw.a(TXT, pa));
            }
        }
    }

    // ---- layout tab ----
    ItemStack sampleStack() {
        if (AuctionManager.draftItem != null) return AuctionManager.draftStack;
        if (SAMPLE == null) SAMPLE = new ItemStack(Items.DIAMOND);
        return SAMPLE;
    }

    void moveTo(double mx, double my) {
        MotionsConfig cfg = MotionsConfig.get();
        float sc = cfg.hudScale, mg = AuctionHud.MARGIN;
        float px = (float) ((mx - miniX) / miniK) - grabX, py = (float) ((my - miniY) / miniK) - grabY;
        float rangeX = Math.max(1f, width - AuctionHud.W * sc - 2 * mg);
        float rangeY = Math.max(1f, height - AuctionHud.H * sc - 2 * mg);
        float fx = Math.max(0f, Math.min(1f, (px - mg) / rangeX));
        float fy = Math.max(0f, Math.min(1f, (py - mg) / rangeY));
        if (fx < 0.02f) fx = 0f; else if (fx > 0.98f) fx = 1f;
        if (fy < 0.02f) fy = 0f; else if (fy > 0.98f) fy = 1f;
        snapX = Math.abs(fx - 0.5f) < 0.03f; if (snapX) fx = 0.5f;
        snapY = Math.abs(fy - 0.5f) < 0.03f; if (snapY) fy = 0.5f;
        cfg.hudX = fx;
        cfg.hudY = fy;
    }

    void updateScale(double mx) {
        float f = (float) Math.max(0, Math.min(1, (mx - scaleX) / scaleW));
        MotionsConfig.get().hudScale = Math.round((0.5f + f) * 20f) / 20f; // 0.50x .. 1.50x
    }

    void layoutPage(DrawContext c, int mx, int my, float pa, int ac, boolean live, int ty) {
        MotionsConfig cfg = MotionsConfig.get();
        int cx = wx + SB + 1, cw = ww - SB - 1, lx = cx + 16;
        title(c, "HUD layout", lx, ty, pa, ac);
        Draw.text(c, "Drag the card in the preview to move it.", lx, ty + 12, Draw.a(MUTED, pa));

        // mini version of your screen
        float aspect = (float) width / Math.max(1, height);
        int mh = Math.max(70, Math.min(120, wh - 130));
        int mw = Math.round(mh * aspect);
        int maxW = Math.max(110, cw - 32 - 150);
        if (mw > maxW) { mw = maxW; mh = Math.round(mw / aspect); }
        miniX = lx; miniY = ty + 30; miniW = mw; miniH = mh; miniK = (float) mw / Math.max(1, width);
        if (live) hit("mini", miniX, miniY, mw, mh);
        Draw.border(c, miniX, miniY, mw, mh, 6, Draw.a(0xFF090B0E, pa), Draw.a(LINE, pa));
        float gl = 0.07f;
        int vLine = Draw.a(dragMove && snapX ? ac : 0xFFFFFFFF, pa * (dragMove && snapX ? 0.7f : gl));
        int hLine = Draw.a(dragMove && snapY ? ac : 0xFFFFFFFF, pa * (dragMove && snapY ? 0.7f : gl));
        c.fill(miniX + mw / 2, miniY + 3, miniX + mw / 2 + 1, miniY + mh - 3, vLine);
        c.fill(miniX + 3, miniY + mh / 2, miniX + mw - 3, miniY + mh / 2 + 1, hLine);
        int hbw = Math.round(182 * miniK), hbh = Math.max(2, Math.round(22 * miniK));
        Draw.round(c, miniX + (mw - hbw) / 2, miniY + mh - hbh - 3, hbw, hbh, 2, Draw.a(0xFF1B1F25, pa)); // hotbar hint

        float sc = cfg.hudScale;
        float ks = miniK * sc;
        int rx = AuctionHud.posX(width, sc), ry = AuctionHud.posY(height, sc);
        int cardW = Math.max(4, Math.round(AuctionHud.W * ks)), cardH = Math.max(4, Math.round(AuctionHud.H * ks));
        int cxm = miniX + Math.round(rx * miniK), cym = miniY + Math.round(ry * miniK);
        float hv = hov("minicard", dragMove || (live && Draw.in(mx, my, cxm, cym, cardW, cardH)));
        Draw.round(c, cxm - 2, cym - 2, cardW + 4, cardH + 4, 5, Draw.a(ac, pa * (0.08f + 0.22f * hv)));
        var m = c.getMatrices();
        m.pushMatrix();
        m.translate(cxm, cym);
        m.scale(ks, ks);
        AuctionHud.drawCard(c, 0, 0, AuctionHud.W, sampleStack(), "No bids", "Min 450M", "0:59", "Preview", 1f, pa, ac, 0f, 0f);
        m.popMatrix();

        String info = String.format(Locale.ROOT, "Position %d%% / %d%%    Scale %.2fx",
                Math.round(cfg.hudX * 100), Math.round(cfg.hudY * 100), sc);
        Draw.text(c, info, lx, miniY + mh + 8, Draw.a(TXT, pa));
        Draw.text(c, "Saved automatically. Snaps to the centre.", lx, miniY + mh + 20, Draw.a(MUTED, pa));

        // controls on the right
        int rx0 = lx + mw + 16, rw = cx + cw - 16 - rx0;
        if (rw < 90) return;
        int y = miniY;
        Draw.text(c, "Scale", rx0, y, Draw.a(MUTED, pa));
        String sv = String.format(Locale.ROOT, "%.2fx", sc);
        Draw.text(c, sv, rx0 + rw - Draw.width(sv), y, Draw.a(TXT, pa));
        scaleX = rx0; scaleW = rw;
        float f = (sc - 0.5f);
        float sh = hov("lscale", dragScale || (live && Draw.in(mx, my, rx0 - 4, y + 8, rw + 8, 16)));
        if (live) hit("lscale", rx0 - 4, y + 8, rw + 8, 16);
        Draw.round(c, rx0, y + 14, rw, 4, 2, Draw.a(0xFF2B3038, pa));
        Draw.round(c, rx0, y + 14, Math.max(2, Math.round(rw * f)), 4, 2, Draw.a(ac, pa));
        int kx = rx0 + Math.round(rw * f) - 4;
        int ke = (int) Math.ceil(sh);
        Draw.round(c, kx - ke, y + 12 - ke, 8 + 2 * ke, 8 + 2 * ke, 4, Draw.a(0xFFFFFFFF, pa));
        y += 34;

        Draw.text(c, "Snap to", rx0, y, Draw.a(MUTED, pa));
        y += 12;
        String[] al = {"TL", "TC", "TR", "BL", "BC", "BR"};
        int aw = (rw - 8) / 3, ah = 18;
        for (int i = 0; i < 6; i++) {
            int bx = rx0 + (i % 3) * (aw + 4), by = y + (i / 3) * (ah + 4);
            float av = hov("anch" + i, live && Draw.in(mx, my, bx, by, aw, ah));
            if (live) hit("anch:" + i, bx, by, aw, ah);
            Draw.border(c, bx, by, aw, ah, 5, Draw.a(Draw.mix(PANEL, PANEL2, av), pa), Draw.a(Draw.mix(LINE, ac, av * 0.8f), pa));
            Draw.centered(c, al[i], bx + aw / 2, by + 5, Draw.a(Draw.mix(MUTED, TXT, av), pa), false);
        }
        y += 2 * (ah + 4) + 4;

        float rv = hov("lreset", live && Draw.in(mx, my, rx0, y, rw, 20));
        if (live) hit("lreset", rx0, y, rw, 20);
        Draw.border(c, rx0, y, rw, 20, 6, Draw.a(Draw.mix(PANEL, PANEL2, rv), pa), Draw.a(Draw.mix(LINE, DANGER, rv * 0.7f), pa));
        Draw.centered(c, "Reset layout", rx0 + rw / 2, y + 6, Draw.a(Draw.mix(MUTED, TXT, rv), pa), false);
    }

    // ---- settings page ----
    void rowBg(DrawContext c, float pa, int x, int y, int w, String label, String desc) {
        Draw.border(c, x, y, w, 26, 7, Draw.a(PANEL, pa), Draw.a(LINE, pa));
        Draw.text(c, label, x + 10, y + 4, Draw.a(TXT, pa));
        Draw.text(c, desc, x + 10, y + 14, Draw.a(MUTED, pa));
    }

    void toggleRow(DrawContext c, int mx, int my, float pa, int ac, boolean live, String id, int y,
                   String label, String desc, boolean on, int lx, int rw) {
        rowBg(c, pa, lx, y, rw, label, desc);
        int tw = 28, th = 14, x = lx + rw - 10 - tw, ty = y + 6;
        Anim a = anims.computeIfAbsent("tg" + id, k -> new Anim(on ? 1 : 0));
        a.to(on ? 1f : 0f);
        float t = a.get();
        float hv = hov("tgh" + id, live && Draw.in(mx, my, lx, y, rw, 26));
        if (live) hit("tg:" + id, lx, y, rw, 26);
        Draw.round(c, x, ty, tw, th, 7, Draw.a(Draw.mix(Draw.mix(0xFF2B3038, 0xFF353B44, hv), ac, t), pa));
        Draw.round(c, x + 2 + Math.round(t * (tw - th)), ty + 2, th - 4, th - 4, 5,
                Draw.a(Draw.mix(0xFF8B94A0, 0xFFFFFFFF, t), pa));
    }

    void settingsPage(DrawContext c, int mx, int my, float pa, int ac, boolean live, int ty) {
        MotionsConfig cfg = MotionsConfig.get();
        int cx = wx + SB + 1, cw = ww - SB - 1, lx = cx + 16, rw = cw - 32, rEnd = lx + rw;
        title(c, "Settings", lx, ty, pa, ac);
        Draw.text(c, "Changes are saved automatically.", lx, ty + 12, Draw.a(MUTED, pa));
        int y = ty + 30, rh = 30;

        toggleRow(c, mx, my, pa, ac, live, "notif", y, "Auction notifications", "Action-bar alerts for start, bids and end.", cfg.notifications, lx, rw); y += rh;
        toggleRow(c, mx, my, pa, ac, live, "hud", y, "Auction display", "Show the auction card on screen.", cfg.showAuctionHud, lx, rw); y += rh;
        toggleRow(c, mx, my, pa, ac, live, "sounds", y, "UI sounds", "Hover and click sound effects.", cfg.sounds, lx, rw); y += rh;
        toggleRow(c, mx, my, pa, ac, live, "anim", y, "Animations", "Turn off for instant UI changes.", cfg.animations, lx, rw); y += rh;

        // speed slider
        rowBg(c, pa, lx, y, rw, "Animation speed", "Faster or slower motion.");
        sliderW = 90; sliderX = rEnd - 10 - sliderW - 34;
        float frac = (cfg.animSpeed - 0.5f) / 1.5f;
        Draw.round(c, sliderX, y + 11, sliderW, 4, 2, Draw.a(0xFF2B3038, pa));
        Draw.round(c, sliderX, y + 11, Math.max(2, Math.round(sliderW * frac)), 4, 2, Draw.a(ac, pa));
        float sh = hov("slider", dragSlider || (live && Draw.in(mx, my, sliderX - 4, y + 4, sliderW + 8, 18)));
        int kx = sliderX + Math.round(sliderW * frac) - 4;
        Draw.round(c, kx - (int) sh, y + 9 - (int) sh, 8 + 2 * (int) Math.ceil(sh), 8 + 2 * (int) Math.ceil(sh), 4, Draw.a(0xFFFFFFFF, pa));
        Draw.text(c, String.format(Locale.ROOT, "%.2fx", cfg.animSpeed), rEnd - 10 - 30, y + 9, Draw.a(TXT, pa));
        if (live) hit("slider", sliderX - 4, y + 4, sliderW + 8, 18);
        y += rh;

        // accent swatches
        rowBg(c, pa, lx, y, rw, "Accent colour", "Customise the UI highlight colour.");
        for (int i = 0; i < MotionsConfig.ACCENTS.length; i++) {
            int x = rEnd - 10 - (MotionsConfig.ACCENTS.length - i) * 20 + 4, sy = y + 6;
            boolean sel = cfg.accent == MotionsConfig.ACCENTS[i];
            Anim a = anims.computeIfAbsent("acc" + i, k -> new Anim(0));
            a.to(sel ? 1f : 0f);
            float t = a.get();
            float hv = hov("acch" + i, live && Draw.in(mx, my, x - 2, sy - 2, 18, 18));
            if (live) hit("acc:" + i, x - 2, sy - 2, 18, 18);
            Draw.round(c, x - 2, sy - 2, 18, 18, 6, Draw.a(0xFFFFFFFF, pa * Math.max(t, hv * 0.4f)));
            Draw.round(c, x, sy, 14, 14, 5, Draw.a(MotionsConfig.ACCENTS[i], pa));
        }
        y += rh;
    }

    // ---- item picker popup ----
    void closePopup() { if (popupOpen) Sfx.close(); popupOpen = false; search.setFocused(false); }

    void popupLayer(DrawContext c, int mx, int my, float delta, int ac, float pv) {
        c.createNewRootLayer(); // draw the popup strictly above everything drawn so far
        c.fill(0, 0, width, height, Draw.a(0xB0000000, pv));
        pw = Math.min(510, width - 20);
        ph = Math.min(310, height - 20);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + Math.round((1f - pv) * 14);
        Draw.border(c, px, py, pw, ph, 10, Draw.a(0xFF0B0D10, pv), Draw.a(LINE, pv));
        boolean act = popupOpen && pv > 0.5f;
        int sb = 104;

        // sidebar
        pHi.to(pTab);
        Draw.border(c, px + 8, py + 14 + Math.round(pHi.get() * 24), sb - 16, 22, 6, Draw.a(PANEL2, pv),
                Draw.a(Draw.mix(LINE, ac, 0.55f), pv));
        for (int i = 0; i < 6; i++) {
            int y = py + 14 + i * 24;
            float hv = hov("pt" + i, act && Draw.in(mx, my, px + 8, y, sb - 16, 22));
            if (act) hit("ptab:" + i, px + 8, y, sb - 16, 22);
            if (pv > 0.6f) Draw.item(c, popStacks()[i], px + 14, y + 5, 0.75f);
            Draw.text(c, P_NAMES[i], px + 32, y + 7, Draw.a(Draw.mix(MUTED, TXT, Math.max(hv, i == pTab ? 1f : 0f)), pv));
        }
        int fy = py + ph - 34;
        c.fill(px + 10, fy - 6, px + sb - 10, fy - 5, Draw.a(LINE, pv));
        Draw.round(c, px + 10, fy + 2, 20, 20, 5, Draw.a(PANEL2, pv));
        Draw.text(c, textRenderer.trimToWidth(client.getSession().getUsername(), sb - 44), px + 36, fy + 4, Draw.a(TXT, pv));
        Draw.text(c, "Local profile", px + 36, fy + 14, Draw.a(MUTED, pv));
        c.fill(px + sb, py + 1, px + sb + 1, py + ph - 1, Draw.a(LINE, pv));

        // search + close
        int bx = px + sb + 11, by = py + 10, bw = pw - sb - 11 - 10 - 24;
        boolean foc = search.isFocused();
        float sh = hov("sbox", foc || (act && Draw.in(mx, my, bx, by, bw, 22)));
        if (act) hit("psearch", bx, by, bw, 22);
        Draw.border(c, bx, by, bw, 22, 6, Draw.a(PANEL, pv), Draw.a(Draw.mix(LINE, ac, sh), pv));
        search.setX(bx + 8); search.setY(by + 7); search.setWidth(bw - 16); search.setHeight(10);
        if (pv > 0.3f) search.render(c, mx, my, delta);
        if (search.getText().isEmpty() && !foc) Draw.text(c, "Search items...", bx + 8, by + 7, Draw.a(MUTED, pv * 0.8f));

        int cxb = px + pw - 10 - 18;
        float ch = hov("pclose", act && Draw.in(mx, my, cxb, by + 2, 18, 18));
        if (act) hit("pclose", cxb, by + 2, 18, 18);
        Draw.border(c, cxb, by + 2, 18, 18, 6, Draw.a(Draw.mix(PANEL, 0xFF3A1D1D, ch), pv), Draw.a(Draw.mix(LINE, DANGER, ch), pv));
        Draw.centered(c, "x", cxb + 9, by + 7, Draw.a(Draw.mix(MUTED, DANGER, ch), pv), true);

        // grid
        gx = px + sb + 11;
        gy = by + 22 + 10;
        gw = pw - sb - 11 - 10 - 6;
        gh = py + ph - 10 - gy;
        cols = Math.max(1, gw / cell);
        gx += (gw - cols * cell) / 2;
        int rows = (shown.size() + cols - 1) / cols;
        float contentH = rows * cell;
        maxScroll = Math.max(0, contentH - gh);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.to(scrollTarget);
        scrollCur = scroll.get();
        float gf = gridFade.get();

        int hoverIdx = act ? entryAt(mx, my) : -1;
        if (hoverIdx != lastHoverIdx) { if (hoverIdx >= 0) Sfx.hover(); lastHoverIdx = hoverIdx; }

        if (shown.isEmpty()) {
            Draw.centered(c, "No items found", gx + gw / 2, gy + 30, Draw.a(MUTED, pv * gf), false);
        } else {
            c.enableScissor(gx - 2, gy, gx + cols * cell + 2, gy + gh);
            int first = Math.max(0, (int) Math.floor(scrollCur / cell));
            int last = Math.min(rows - 1, (int) Math.ceil((scrollCur + gh) / (float) cell));
            for (int r = first; r <= last; r++) {
                for (int col = 0; col < cols; col++) {
                    int idx = r * cols + col;
                    if (idx >= shown.size()) break;
                    Entry e = shown.get(idx);
                    int ix = gx + col * cell;
                    int iy = gy + Math.round(r * cell - scrollCur + (1f - gf) * 8);
                    boolean hv = idx == hoverIdx;
                    if (hv) hoverEntry = e;
                    Anim ha = itemHover.get(e);
                    if (ha == null && hv) { ha = new Anim(0); itemHover.put(e, ha); }
                    float h = 0;
                    if (ha != null) {
                        ha.to(hv ? 1f : 0f);
                        h = ha.get();
                        if (!hv && h == 0f) itemHover.remove(e);
                    }
                    boolean isSel = e.item == AuctionManager.draftItem;
                    float a = pv * gf;
                    if (isSel) Draw.border(c, ix + 1, iy + 1, cell - 2, cell - 2, 6, Draw.a(Draw.mix(PANEL, ac, 0.25f), a), Draw.a(ac, a));
                    else if (h > 0.01f) Draw.border(c, ix + 1, iy + 1, cell - 2, cell - 2, 6, Draw.a(Draw.mix(PANEL, PANEL2, h), a * h), Draw.a(Draw.mix(LINE, ac, 0.6f), a * h));
                    if (gf > 0.4f) {
                        float s = 1f + 0.22f * h, size = 16 * s;
                        Draw.item(c, e.stack, ix + (cell - size) / 2f, iy + (cell - size) / 2f, s);
                    }
                }
            }
            c.disableScissor();
        }
        if (maxScroll > 0) {
            int th = Math.max(16, (int) (gh * (gh / contentH)));
            int ty = gy + (int) ((scrollCur / maxScroll) * (gh - th));
            Draw.round(c, px + pw - 10 - 4, gy, 4, gh, 2, Draw.a(0xFF181B20, pv));
            Draw.round(c, px + pw - 10 - 4, ty, 4, th, 2, Draw.a(Draw.mix(0xFF3A4048, ac, 0.6f), pv));
        }
    }

    static final String[] TAB_NAMES = {"Auction", "Layout", "Settings"};
    static ItemStack[] TAB_ST, POP_ST;

    static ItemStack[] tabStacks() {
        if (TAB_ST == null) TAB_ST = new ItemStack[]{new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.COMPASS), new ItemStack(Items.COMPARATOR)};
        return TAB_ST;
    }

    static ItemStack[] popStacks() {
        if (POP_ST == null) {
            Item[] ic = P_ICONS();
            POP_ST = new ItemStack[ic.length];
            for (int i = 0; i < ic.length; i++) POP_ST[i] = new ItemStack(ic[i]);
        }
        return POP_ST;
    }

    static Item[] ICONS;
    static Item[] P_ICONS() {
        if (ICONS == null) ICONS = new Item[]{Items.SPAWNER, Items.ELYTRA, Items.CHEST, Items.FILLED_MAP, Items.BARRIER, Items.COMPARATOR};
        return ICONS;
    }

    int entryAt(double mx, double my) {
        if (mx < gx || mx >= gx + cols * cell || my < gy || my >= gy + gh) return -1;
        int col = (int) ((mx - gx) / cell);
        int row = (int) Math.floor((my - gy + scrollCur) / cell);
        int i = row * cols + col;
        return i >= 0 && i < shown.size() ? i : -1;
    }

    // ---- input ----
    void click(String id, Click ck) {
        double mx = ck == null ? 0 : ck.x();
        double my = ck == null ? 0 : ck.y();
        MotionsConfig cfg = MotionsConfig.get();
        if (id.startsWith("tab:")) {
            int t = Integer.parseInt(id.substring(4));
            if (t != tab) { tab = t; page.snap(0); price.setFocused(false); }
        } else if (id.equals("item")) {
            popupOpen = true;
            Sfx.open();
            price.setFocused(false);
            search.setFocused(true);
        } else if (id.startsWith("chip:")) {
            price.setText(CHIPS[Integer.parseInt(id.substring(5))]);
        } else if (id.startsWith("dur:")) {
            AuctionManager.draftDuration = DUR[Integer.parseInt(id.substring(4))];
        } else if (id.equals("pricebox")) {
            price.setFocused(true); if (ck != null) price.mouseClicked(ck, false);
        } else if (id.equals("start")) {
            if (AuctionManager.state == AuctionManager.State.ACTIVE) {
                AuctionManager.stop();
                setStatus("Auction stopped.", MUTED);
            } else {
                String err = AuctionManager.start(AuctionManager.draftItem, AuctionManager.parsePrice(price.getText()), AuctionManager.draftDuration);
                if (err != null) { setStatus(err, DANGER); Sfx.error(); }
                else close(); // the card pops onto your screen as the menu closes
            }
        } else if (id.startsWith("tg:")) {
            switch (id.substring(3)) {
                case "notif" -> { cfg.notifications = !cfg.notifications; Sfx.toggle(cfg.notifications); }
                case "hud" -> { cfg.showAuctionHud = !cfg.showAuctionHud; Sfx.toggle(cfg.showAuctionHud); }
                case "sounds" -> { cfg.sounds = !cfg.sounds; Sfx.toggle(cfg.sounds); }
                case "anim" -> { cfg.animations = !cfg.animations; Sfx.toggle(cfg.animations); }
                default -> { }
            }
            MotionsConfig.save();
        } else if (id.startsWith("acc:")) {
            cfg.accent = MotionsConfig.ACCENTS[Integer.parseInt(id.substring(4))];
            MotionsConfig.save();
        } else if (id.equals("slider")) {
            dragSlider = true; updateSlider(mx);
        } else if (id.equals("mini")) {
            float sc = cfg.hudScale;
            float curX = (float) ((mx - miniX) / miniK), curY = (float) ((my - miniY) / miniK);
            float rx = AuctionHud.posX(width, sc), ry = AuctionHud.posY(height, sc);
            float cw2 = AuctionHud.W * sc, ch2 = AuctionHud.H * sc;
            if (curX >= rx && curX < rx + cw2 && curY >= ry && curY < ry + ch2) { grabX = curX - rx; grabY = curY - ry; }
            else { grabX = cw2 / 2f; grabY = ch2 / 2f; }
            dragMove = true;
            moveTo(mx, my);
        } else if (id.equals("lscale")) {
            dragScale = true; updateScale(mx);
        } else if (id.startsWith("anch:")) {
            int i = Integer.parseInt(id.substring(5));
            cfg.hudX = (i % 3) * 0.5f;
            cfg.hudY = i < 3 ? 0f : 1f;
            MotionsConfig.save();
        } else if (id.equals("lreset")) {
            cfg.hudX = 0.5f; cfg.hudY = 0f; cfg.hudScale = 1f;
            MotionsConfig.save();
        } else if (id.equals("pclose")) {
            closePopup();
        } else if (id.equals("psearch")) {
            search.setFocused(true); if (ck != null) search.mouseClicked(ck, false);
        } else if (id.startsWith("ptab:")) {
            int t = Integer.parseInt(id.substring(5));
            if (t == 5) { closePopup(); tab = 2; page.snap(0); }
            else if (t != pTab) { pTab = t; refilter(); }
        }
    }

    void updateSlider(double mx) {
        float f = (float) Math.max(0, Math.min(1, (mx - sliderX) / sliderW));
        MotionsConfig.get().animSpeed = Math.round((0.5f + 1.5f * f) * 20f) / 20f;
    }

    @Override
    public boolean mouseClicked(Click ck, boolean doubled) {
        double mx = ck.x(), my = ck.y();
        if (ck.button() != 0 || closing) return true;
        if (popupOpen) {
            if (!(mx >= px && mx < px + pw && my >= py && my < py + ph)) { closePopup(); return true; }
            for (Hit h : hits) if (h.in(mx, my)) { hitSound(h.id()); click(h.id(), ck); return true; }
            int idx = entryAt(mx, my);
            if (idx >= 0) {
                AuctionManager.setDraft(shown.get(idx).item);
                Sfx.select();
                popupCloseAt = System.currentTimeMillis() + 240; // let the selection highlight show briefly
            }
            return true;
        }
        for (Hit h : hits) if (h.in(mx, my)) { hitSound(h.id()); click(h.id(), ck); return true; }
        price.setFocused(false);
        return true;
    }

    @Override
    public boolean mouseDragged(Click ck, double dx, double dy) {
        if (dragSlider) { updateSlider(ck.x()); return true; }
        if (dragMove) { moveTo(ck.x(), ck.y()); return true; }
        if (dragScale) { updateScale(ck.x()); return true; }
        return false;
    }

    @Override
    public boolean mouseReleased(Click ck) {
        if (dragSlider || dragMove || dragScale) { dragSlider = false; dragMove = false; dragScale = false; MotionsConfig.save(); }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (popupOpen) {
            scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget - (float) vAmt * 34f));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        if (input.isEscape()) {
            if (popupOpen) closePopup(); else close();
            return true;
        }
        if (popupOpen) { search.keyPressed(input); return true; }
        if (price.keyPressed(input)) return true;
        if (input.isEnter() && price.isFocused()) { click("start", null); return true; }
        return false;
    }

    @Override
    public boolean charTyped(CharInput input) {
        if (popupOpen) return search.charTyped(input);
        return price.charTyped(input);
    }
}
