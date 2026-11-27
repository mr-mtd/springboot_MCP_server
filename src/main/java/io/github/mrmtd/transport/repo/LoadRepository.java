package io.github.mrmtd.transport.repo;

import io.github.mrmtd.transport.model.DelayedShipment;
import io.github.mrmtd.transport.model.LoadEventSummary;
import io.github.mrmtd.transport.model.LoadStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Read-only queries over transport.load and transport.load_event. */
@Repository
public class LoadRepository {

    private final JdbcClient jdbc;

    public LoadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One load with its carrier name and most recent event, or empty if the load number is unknown. */
    public Optional<LoadStatus> findStatus(String loadNumber) {
        String sql = """
                SELECT l.load_number, l.status, l.carrier_id, c.name AS carrier_name, l.equipment_type,
                       l.origin_city, l.origin_state, l.destination_city, l.destination_state, l.miles,
                       l.pickup_scheduled_at, l.picked_up_at, l.delivery_scheduled_at, l.delivered_at,
                       %s AS hours_late,
                       e.event_type, e.occurred_at, e.city, e.state, e.latitude, e.longitude,
                       e.delay_reason, e.notes
                FROM transport.load l
                LEFT JOIN transport.carrier c ON c.id = l.carrier_id
                LEFT JOIN LATERAL (
                    SELECT * FROM transport.load_event ev
                    WHERE ev.load_id = l.id
                    ORDER BY ev.occurred_at DESC, ev.id DESC
                    LIMIT 1
                ) e ON true
                WHERE l.load_number = :loadNumber
                """.formatted(DelayRule.HOURS_LATE_SQL);

        return QueryLog.timed("load.findStatus", () -> jdbc.sql(sql)
                .param("loadNumber", loadNumber)
                .query((rs, n) -> {
                    Double rawHoursLate = getDouble(rs, "hours_late");
                    LoadEventSummary lastEvent = rs.getString("event_type") == null ? null : new LoadEventSummary(
                            rs.getString("event_type"),
                            rs.getObject("occurred_at", OffsetDateTime.class),
                            rs.getString("city"),
                            trim(rs.getString("state")),
                            rs.getBigDecimal("latitude"),
                            rs.getBigDecimal("longitude"),
                            rs.getString("delay_reason"),
                            rs.getString("notes"));
                    return new LoadStatus(
                            rs.getString("load_number"),
                            rs.getString("status"),
                            getLong(rs, "carrier_id"),
                            rs.getString("carrier_name"),
                            rs.getString("equipment_type"),
                            rs.getString("origin_city"),
                            trim(rs.getString("origin_state")),
                            rs.getString("destination_city"),
                            trim(rs.getString("destination_state")),
                            getInt(rs, "miles"),
                            rs.getObject("pickup_scheduled_at", OffsetDateTime.class),
                            rs.getObject("picked_up_at", OffsetDateTime.class),
                            rs.getObject("delivery_scheduled_at", OffsetDateTime.class),
                            rs.getObject("delivered_at", OffsetDateTime.class),
                            DelayRule.display(rawHoursLate),
                            DelayRule.isLate(rawHoursLate),
                            lastEvent);
                })
                .optional(), r -> r.isPresent() ? 1 : 0);
    }

    /**
     * Late loads more than {@code minHoursLate} hours late, most late first. Comparison and sorting use
     * the raw hours; only the returned value is rounded. Null filters are ignored.
     */
    public List<DelayedShipment> findDelayed(double minHoursLate, Long carrierId, String originState,
                                             String destinationState, boolean includeDelivered, int limit) {
        String sql = """
                SELECT * FROM (
                    SELECT l.load_number, l.status, l.carrier_id, c.name AS carrier_name,
                           l.origin_city, l.origin_state, l.destination_city, l.destination_state,
                           l.delivery_scheduled_at, l.delivered_at,
                           %s AS hours_late,
                           (SELECT ev.delay_reason FROM transport.load_event ev
                             WHERE ev.load_id = l.id AND ev.event_type = 'DELAY_REPORTED'
                             ORDER BY ev.occurred_at DESC LIMIT 1) AS latest_delay_reason
                    FROM transport.load l
                    LEFT JOIN transport.carrier c ON c.id = l.carrier_id
                    WHERE l.status IN ('IN_TRANSIT', 'DELIVERED')
                      AND (:includeDelivered OR l.status <> 'DELIVERED')
                      AND (CAST(:carrierId AS bigint) IS NULL OR l.carrier_id = :carrierId)
                      AND (CAST(:originState AS text) IS NULL OR l.origin_state = :originState)
                      AND (CAST(:destinationState AS text) IS NULL OR l.destination_state = :destinationState)
                ) late
                WHERE late.hours_late > :lateThresholdHours
                  AND late.hours_late > :minHoursLate
                ORDER BY late.hours_late DESC, late.load_number
                LIMIT :limit
                """.formatted(DelayRule.HOURS_LATE_SQL);

        return QueryLog.timed("load.findDelayed", () -> jdbc.sql(sql)
                .param("lateThresholdHours", DelayRule.LATE_THRESHOLD_HOURS)
                .param("minHoursLate", minHoursLate)
                .param("includeDelivered", includeDelivered)
                .param("carrierId", carrierId)
                .param("originState", originState)
                .param("destinationState", destinationState)
                .param("limit", limit)
                .query((rs, n) -> new DelayedShipment(
                        rs.getString("load_number"),
                        rs.getString("status"),
                        getLong(rs, "carrier_id"),
                        rs.getString("carrier_name"),
                        rs.getString("origin_city"),
                        trim(rs.getString("origin_state")),
                        rs.getString("destination_city"),
                        trim(rs.getString("destination_state")),
                        rs.getObject("delivery_scheduled_at", OffsetDateTime.class),
                        rs.getObject("delivered_at", OffsetDateTime.class),
                        DelayRule.display(rs.getDouble("hours_late")),
                        rs.getString("latest_delay_reason")))
                .list(), List::size);
    }

    static Long getLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Integer getInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    static Double getDouble(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    /** char(2) columns come back as plain strings; trim defensively in case of padding. */
    static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
