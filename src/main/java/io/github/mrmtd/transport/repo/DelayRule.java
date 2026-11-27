package io.github.mrmtd.transport.repo;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one definition of "late", used by every tool so they all agree.
 *
 * <p>Hours late = (delivered_at, or now() while still on the road) minus delivery_scheduled_at.
 * A load is late when that raw, unrounded value is strictly greater than {@link #LATE_THRESHOLD_HOURS}.
 * Cancelled loads, and loads not yet picked up, are never late.
 *
 * <p>All comparisons, filters and sorting use the raw value. Rounding to one decimal happens only
 * when a value is put into a response ({@link #display(double)}), so a load 2.04 hours late is late
 * and shows as 2.0.
 */
public final class DelayRule {

    /** Grace period before a load counts as late, in hours. */
    public static final double LATE_THRESHOLD_HOURS = 2.0;

    /** Raw hours late for the {@code load} table aliased as {@code l}; null when not applicable. Never rounded. */
    static final String HOURS_LATE_SQL = """
            CASE WHEN l.status IN ('IN_TRANSIT', 'DELIVERED')
                 THEN extract(epoch FROM coalesce(l.delivered_at, now()) - l.delivery_scheduled_at) / 3600
            END""";

    /** SQL predicate: the load is late. Binds {@code :lateThresholdHours}. */
    static final String IS_LATE_SQL = "(" + HOURS_LATE_SQL + ") > :lateThresholdHours";

    private DelayRule() {
    }

    /** The Java side of the same rule, on the raw value. */
    public static boolean isLate(Double rawHoursLate) {
        return rawHoursLate != null && rawHoursLate > LATE_THRESHOLD_HOURS;
    }

    /** Rounds a raw value to one decimal for display. */
    public static double display(double rawHours) {
        return BigDecimal.valueOf(rawHours).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    public static Double display(Double rawHours) {
        return rawHours == null ? null : display(rawHours.doubleValue());
    }
}
