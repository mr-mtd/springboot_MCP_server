package io.github.mrmtd.transport.repo;

/**
 * The one definition of "late" used by every tool, so they all agree.
 *
 * <p>Hours late = (delivered_at, or now() while still on the road) minus delivery_scheduled_at.
 * A load counts as late when that is more than {@link #LATE_THRESHOLD_HOURS}. Cancelled loads,
 * and loads not yet picked up, are never late.
 */
public final class DelayRule {

    /** Grace period before a load counts as late, in hours. */
    public static final double LATE_THRESHOLD_HOURS = 2.0;

    /** SQL expression for hours late on the {@code load} table aliased as {@code l}; null when not applicable. */
    static final String HOURS_LATE_SQL = """
            CASE WHEN l.status IN ('IN_TRANSIT', 'DELIVERED')
                 THEN round((extract(epoch FROM coalesce(l.delivered_at, now()) - l.delivery_scheduled_at) / 3600)::numeric, 1)
            END""";

    private DelayRule() {
    }
}
