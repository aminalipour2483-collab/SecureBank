package com.bank.view;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * UITheme — central design constants for the professional English (LTR) theme.
 *
 * Design language: modern retail-banking console.
 *   • Typography : Segoe UI (falls back to Arial / DejaVu Sans / SansSerif)
 *   • Palette    : deep institutional blue, confident green for success,
 *                  signal red for danger, amber for warnings
 *   • Surfaces   : white cards on a light grey-blue canvas
 */
public final class UITheme {

    private UITheme() {}

    // ── Palette ──────────────────────────────────────────────────────────────
    public static final Color PRIMARY        = new Color(0x1B, 0x4B, 0x82);   // institutional blue
    public static final Color PRIMARY_DARK   = new Color(0x14, 0x3A, 0x66);
    public static final Color PRIMARY_LIGHT  = new Color(0xE8, 0xF0, 0xF8);   // tint for banners
    public static final Color ACCENT         = new Color(0x16, 0xA3, 0x4A);   // success green
    public static final Color ACCENT_DARK    = new Color(0x12, 0x80, 0x3C);
    public static final Color DANGER         = new Color(0xDC, 0x26, 0x26);   // signal red
    public static final Color DANGER_DARK    = new Color(0xB9, 0x1C, 0x1C);
    public static final Color DANGER_PRESSED = new Color(0x99, 0x1B, 0x1B);
    public static final Color WARNING        = new Color(0xD9, 0x77, 0x06);   // amber
    public static final Color SURFACE        = new Color(0xF4, 0xF6, 0xF9);   // app canvas
    public static final Color CARD           = java.awt.Color.WHITE;
    public static final Color BORDER         = new Color(0xD9, 0xDE, 0xE7);
    public static final Color TEXT_PRIMARY   = new Color(0x1A, 0x23, 0x32);
    public static final Color TEXT_SECONDARY = new Color(0x5F, 0x6B, 0x7A);
    public static final Color TEXT_ON_DARK   = java.awt.Color.WHITE;

    public static final Color HEADER_BG      = new Color(0x12, 0x28, 0x3F);   // table headers
    public static final Color HEADER_FG      = java.awt.Color.WHITE;

    // ── Typography ───────────────────────────────────────────────────────────
    /** Segoe UI on Windows, Arial elsewhere, logical SansSerif as last resort. */
    public static final String FONT_FAMILY = resolveFontFamily();

    public static final Font HEADER_FONT     = new Font(FONT_FAMILY, Font.BOLD, 12);
    public static final Font FONT_HEADING    = new Font(FONT_FAMILY, Font.BOLD, 22);
    public static final Font FONT_SUBHEADING = new Font(FONT_FAMILY, Font.BOLD, 15);
    public static final Font FONT_BODY       = new Font(FONT_FAMILY, Font.PLAIN, 13);
    public static final Font FONT_SMALL      = new Font(FONT_FAMILY, Font.PLAIN, 11);
    public static final Font FONT_BUTTON     = new Font(FONT_FAMILY, Font.BOLD, 13);
    public static final Font FONT_MONO       = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    public static final Font FONT_BALANCE    = new Font(FONT_FAMILY, Font.BOLD, 28);

    // ── Table row colours ────────────────────────────────────────────────────
    public static final Color ROW_CREDIT     = new Color(0xE6, 0xF4, 0xEA);   // money in
    public static final Color ROW_DEBIT      = new Color(0xFD, 0xEC, 0xEB);   // money out / danger
    public static final Color ROW_ALTERNATE  = new Color(0xF7, 0xF9, 0xFC);   // zebra stripe
    public static final Color ROW_WARNING    = new Color(0xFF, 0xF6, 0xE0);   // pending
    public static final Color SELECTION_BG   = new Color(0xCF, 0xE0, 0xF2);

    // ── Sizing ───────────────────────────────────────────────────────────────
    public static final int  PADDING          = 16;
    public static final int  PADDING_SM       = 10;
    public static final int  BUTTON_HEIGHT    = 36;
    public static final int  BUTTON_MIN_WIDTH = 96;   // never let a label truncate
    public static final int  FIELD_HEIGHT     = 34;
    public static final int  CORNER_RADIUS    = 6;

    // ── Branding ─────────────────────────────────────────────────────────────
    public static final String APP_NAME    = "SecureBank";
    public static final String APP_TAGLINE = "Secure. Trusted. Always available.";

    // ── Font resolution ──────────────────────────────────────────────────────
    private static String resolveFontFamily() {
        try {
            String[] available = GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            Arrays.sort(available);
            for (String candidate : new String[]{"Segoe UI", "Arial", "Helvetica", "DejaVu Sans"}) {
                if (Arrays.binarySearch(available, candidate) >= 0) return candidate;
            }
        } catch (Throwable ignored) {}
        return Font.SANS_SERIF;
    }
}
