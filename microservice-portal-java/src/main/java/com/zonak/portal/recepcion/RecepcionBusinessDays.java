package com.zonak.portal.recepcion;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Cálculo de días hábiles Colombia (lun–vie). Sin calendario de festivos en V1.
 */
public final class RecepcionBusinessDays {
    public static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    public static final int TACIT_ACCEPTANCE_BUSINESS_DAYS = 3;

    private RecepcionBusinessDays() {
    }

    public static LocalDate todayBogota() {
        return LocalDate.now(BOGOTA);
    }

    public static LocalDate addBusinessDays(LocalDate start, int businessDays) {
        if (start == null) {
            throw new IllegalArgumentException("Fecha inicial requerida");
        }
        if (businessDays < 0) {
            throw new IllegalArgumentException("businessDays debe ser >= 0");
        }
        LocalDate cursor = start;
        int remaining = businessDays;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) {
                remaining--;
            }
        }
        return cursor;
    }

    public static int businessDaysBetween(LocalDate fromInclusive, LocalDate toExclusive) {
        if (fromInclusive == null || toExclusive == null) {
            return 0;
        }
        if (!toExclusive.isAfter(fromInclusive)) {
            return 0;
        }
        int count = 0;
        LocalDate cursor = fromInclusive;
        while (cursor.isBefore(toExclusive)) {
            cursor = cursor.plusDays(1);
            if (isBusinessDay(cursor)) {
                count++;
            }
        }
        return count;
    }

    public static boolean isBusinessDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
}
