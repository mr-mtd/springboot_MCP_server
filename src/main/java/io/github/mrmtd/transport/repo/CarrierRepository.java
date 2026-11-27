package io.github.mrmtd.transport.repo;

import io.github.mrmtd.transport.model.CarrierDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/** Read-only queries over transport.carrier. */
@Repository
public class CarrierRepository {

    private final JdbcClient jdbc;

    public CarrierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Looks a carrier up by id or by MC number (exactly one should be non-null). */
    public Optional<CarrierDetails> findDetails(Long carrierId, String mcNumber) {
        String sql = """
                SELECT c.*,
                       count(*) FILTER (WHERE l.status IN ('ASSIGNED', 'IN_TRANSIT'))             AS open_loads,
                       count(*) FILTER (WHERE l.status = 'DELIVERED'
                                          AND l.delivered_at >= now() - interval '90 days')        AS delivered_90,
                       count(*) FILTER (WHERE l.status = 'DELIVERED'
                                          AND l.delivered_at >= now() - interval '90 days'
                                          AND l.delivered_at > l.delivery_scheduled_at
                                              + make_interval(secs => :thresholdHours * 3600))     AS late_90
                FROM transport.carrier c
                LEFT JOIN transport.load l ON l.carrier_id = c.id
                WHERE (CAST(:carrierId AS bigint) IS NULL OR c.id = :carrierId)
                  AND (CAST(:mcNumber AS text) IS NULL OR upper(c.mc_number) = upper(:mcNumber))
                GROUP BY c.id
                """;

        return jdbc.sql(sql)
                .param("carrierId", carrierId)
                .param("mcNumber", mcNumber)
                .param("thresholdHours", DelayRule.LATE_THRESHOLD_HOURS)
                .query((rs, n) -> {
                    int delivered = rs.getInt("delivered_90");
                    int late = rs.getInt("late_90");
                    Double onTimePct = delivered == 0 ? null
                            : BigDecimal.valueOf(100.0 * (delivered - late) / delivered)
                                    .setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
                    return new CarrierDetails(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("mc_number"),
                            rs.getString("dot_number"),
                            LoadRepository.trim(rs.getString("scac")),
                            rs.getString("contact_email"),
                            rs.getString("phone"),
                            LoadRepository.trim(rs.getString("home_state")),
                            rs.getString("safety_rating"),
                            rs.getBoolean("active"),
                            rs.getInt("open_loads"),
                            delivered,
                            late,
                            onTimePct);
                })
                .optional();
    }
}
