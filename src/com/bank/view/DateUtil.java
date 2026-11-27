package com.bank.view;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DateUtil — English (Gregorian) date/time and money formatting for every
 * screen. Replaces the former Jalali/Persian date utility.
 */
public final class DateUtil {

    private DateUtil() {}

    private static final DateTimeFormatter DATE  = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATETIME_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String CURRENCY = "IRR";

    // ── Dates ────────────────────────────────────────────────────────────────
    public static String formatDate(LocalDate date) {
        return date == null ? "—" : DATE.format(date);
    }

    public static String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? "—" : DATETIME.format(dateTime);
    }

    public static String formatDateTimeFull(LocalDateTime dateTime) {
        return dateTime == null ? "—" : DATETIME_FULL.format(dateTime);
    }

    // ── Money ────────────────────────────────────────────────────────────────
    /** 2345678 → "2,345,678" (no currency suffix). */
    public static String formatAmount(BigDecimal value) {
        return value == null ? "—" : String.format("%,.2f", value);
    }

    /** 2345678 → "2,345,678 IRR". */
    public static String formatMoney(BigDecimal value) {
        return value == null ? "—" : String.format("%,.2f %s", value, CURRENCY);
    }

    public static String formatMoney(double value) {
        return String.format("%,.2f %s", value, CURRENCY);
    }

    public static String formatMoney(long value) {
        return String.format("%,d %s", value, CURRENCY);
    }
}
