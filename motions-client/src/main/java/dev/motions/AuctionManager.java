package dev.motions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fully client-side auction tracker. Nothing is sent to the server and no item is needed:
 * it only shows a local card with a countdown, and can pick up bids from chat messages.
 */
public final class AuctionManager {
    public enum State { IDLE, ACTIVE, ENDED }

    public static final long WINNER_SHOW_MS = 8000; // how long the winner card stays up

    public static State state = State.IDLE;
    public static ItemStack stack = ItemStack.EMPTY;
    public static long minPrice, currentBid = -1;
    public static int durationSec;
    public static long startMs, endMs, endedAtMs, bidPulseMs;
    public static String currentBidder; // who made the leading payment, if the message named them

    // Draft values edited in the UI
    public static Item draftItem = null;
    public static ItemStack draftStack = ItemStack.EMPTY;
    public static String draftPrice = "";
    public static int draftDuration = 300;

    // Payment messages (only read while an auction is active)
    private static final Pattern AMT = Pattern.compile("\\$?(\\d[\\d,]*(?:\\.\\d+)?)\\s*([kKmMbBtT])?(?![A-Za-z])");
    private static final Pattern PAID_YOU = Pattern.compile("([A-Za-z_]\\w{2,15})\\s+(?:has\\s+|just\\s+)?(?:paid|payed)\\s+you", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEEN_PAID = Pattern.compile("you\\s+(?:have\\s+|'ve\\s+)?(?:been\\s+)?(?:paid|payed)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BY_USER = Pattern.compile("(?:by|from)\\s+([A-Za-z_]\\w{2,15})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAT_LIKE = Pattern.compile("^\\s*(?:<[^>]{1,40}>|(?:\\[[^\\]]{1,20}\\]\\s*)*[A-Za-z0-9_]{3,16}\\s*(?::|\u00bb|>>))");

    public static void setDraft(Item it) {
        draftItem = it;
        draftStack = it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    public static long parsePrice(String s) {
        if (s == null) return -1;
        s = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) return -1;
        double mult = 1;
        char l = s.charAt(s.length() - 1);
        switch (l) {
            case 'k' -> mult = 1e3;
            case 'm' -> mult = 1e6;
            case 'b' -> mult = 1e9;
            case 't' -> mult = 1e12;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        try {
            double v = Double.parseDouble(s) * mult;
            return v > 0 && v < 9e18 ? (long) v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String fmt(long v) {
        if (v >= 1_000_000_000_000L) return trim(v / 1e12) + "T";
        if (v >= 1_000_000_000L) return trim(v / 1e9) + "B";
        if (v >= 1_000_000L) return trim(v / 1e6) + "M";
        if (v >= 1_000L) return trim(v / 1e3) + "K";
        return String.valueOf(v);
    }

    private static String trim(double d) {
        double r = Math.round(d * 10) / 10.0;
        long w = (long) r;
        return r == w ? Long.toString(w) : Double.toString(r);
    }

    private static String two(long v) { return v < 10 ? "0" + v : Long.toString(v); }

    public static String time(long sec) {
        long m = sec / 60, s = sec % 60;
        if (sec >= 3600) return (sec / 3600) + ":" + two((sec / 60) % 60) + ":" + two(s);
        return m + ":" + two(s);
    }

    public static String durationLabel(int sec) {
        if (sec >= 3600) return (sec / 3600) + "h";
        if (sec >= 60) return (sec / 60) + "m";
        return sec + "s";
    }

    /** Starts a local auction. Returns an error message, or null on success. */
    public static String start(Item item, long price, int dur) {
        if (item == null) return "Select an item first.";
        if (price <= 0) return "Enter a valid price (e.g. 450M).";
        if (state == State.ACTIVE) return "An auction is already running.";
        long now = System.currentTimeMillis();
        stack = new ItemStack(item);
        minPrice = price;
        durationSec = dur;
        currentBid = -1;
        currentBidder = null;
        startMs = now;
        endMs = now + dur * 1000L;
        bidPulseMs = now; // start glow
        state = State.ACTIVE;
        Sfx.start();
        notify("Auction started: " + stack.getName().getString() + " (min " + fmt(minPrice) + ")");
        return null;
    }

    public static void stop() {
        if (state == State.ACTIVE) {
            state = State.IDLE;
            notify("Auction stopped.");
        }
    }

    public static void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        long now = System.currentTimeMillis();
        if (state == State.ACTIVE && now >= endMs) {
            state = State.ENDED;
            endedAtMs = now;
            bidPulseMs = now;
            Sfx.end();
            notify(currentBid < 0 ? "Auction ended. No winner." : "Winner: " + (currentBidder != null ? currentBidder : "unknown") + " (" + fmt(currentBid) + ")");
        } else if (state == State.ENDED && now - endedAtMs > WINNER_SHOW_MS) {
            state = State.IDLE;
        }
    }

    /**
     * Reads server messages while an auction is active and looks for payments, e.g.
     * "Steve paid you $500M" or "You have been paid $500M by Steve".
     * Ordinary player chat ("Name: text", "<Name> text") is ignored so it can't be faked.
     */
    public static void onMessage(String msg) {
        if (state != State.ACTIVE || msg == null || msg.length() > 300) return;
        if (CHAT_LIKE.matcher(msg).find()) return;

        String user = null, rest;
        Matcher m = PAID_YOU.matcher(msg);
        if (m.find()) {
            user = m.group(1);
            rest = msg.substring(m.end());
        } else if (BEEN_PAID.matcher(msg).find()) {
            Matcher b = BY_USER.matcher(msg);
            if (b.find()) user = b.group(1);
            rest = msg;
        } else {
            return;
        }
        Matcher am = AMT.matcher(rest);
        if (!am.find()) return;
        long v = parsePrice(am.group(1) + (am.group(2) == null ? "" : am.group(2)));
        if (v <= 0) return;

        String who = user == null ? "Someone" : user;
        AuctionHud.pushPayment("+" + fmt(v) + (user != null ? "  " + user : "")); // little green amount
        Sfx.bid();
        if (v >= minPrice && v > currentBid) {
            currentBid = v;
            currentBidder = user;
            bidPulseMs = System.currentTimeMillis();
            notify(who + " paid you " + fmt(v));
        } else {
            notify(who + " paid " + fmt(v) + (v < minPrice ? " (below min)" : ""));
        }
    }

    private static void notify(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (MotionsConfig.get().notifications && mc.inGameHud != null)
            mc.inGameHud.setOverlayMessage(Text.literal("\u00a7a[Motions] \u00a7f" + msg), false);
    }
}
