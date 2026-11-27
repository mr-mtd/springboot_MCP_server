package io.github.mrmtd.transport;

import io.github.mrmtd.transport.model.CarrierDetails;
import io.github.mrmtd.transport.model.DelayedShipment;
import io.github.mrmtd.transport.model.DelayedShipments;
import io.github.mrmtd.transport.model.LoadStatus;
import io.github.mrmtd.transport.tools.TransportTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Calls the tool methods directly against the seeded database. */
@SpringBootTest
class TransportToolsTest extends PostgresTestBase {

    @Autowired
    TransportTools tools;

    @Autowired
    JdbcClient jdbc;

    @Test
    void seedLoadedExpectedVolumes() {
        assertThat(count("transport.carrier")).isEqualTo(50);
        assertThat(count("transport.load")).isEqualTo(2000);
        assertThat(count("transport.load_event")).isBetween(12_000L, 14_500L);
    }

    @Test
    void appUserIsReadOnly() {
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM transport.load_event").update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
    }

    @Test
    void getLoadStatusAcceptsShortAndFullLoadNumbers() {
        LoadStatus full = tools.getLoadStatus("LD-000420");
        LoadStatus shortForm = tools.getLoadStatus("420");

        assertThat(shortForm).isEqualTo(full);
        assertThat(full.loadNumber()).isEqualTo("LD-000420");
        assertThat(full.status()).isIn("TENDERED", "ASSIGNED", "IN_TRANSIT", "DELIVERED", "CANCELLED");
        assertThat(full.lastEvent()).isNotNull();
        assertThat(full.originState()).hasSize(2);
    }

    @Test
    void getLoadStatusMarksLateDeliveredLoadsLate() {
        String lateLoad = jdbc.sql("""
                        SELECT load_number FROM transport.load
                        WHERE status = 'DELIVERED' AND delivered_at > delivery_scheduled_at + interval '5 hours'
                        ORDER BY id LIMIT 1""")
                .query(String.class).single();

        LoadStatus status = tools.getLoadStatus(lateLoad);

        assertThat(status.late()).isTrue();
        assertThat(status.hoursLate()).isGreaterThan(5.0);
        assertThat(status.lastEvent().eventType()).isEqualTo("DELIVERED");
    }

    @Test
    void getLoadStatusRejectsUnknownAndMalformedLoadNumbers() {
        assertThatThrownBy(() -> tools.getLoadStatus("LD-999999"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No load found");
        assertThatThrownBy(() -> tools.getLoadStatus("truck 7"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("should look like");
        assertThatThrownBy(() -> tools.getLoadStatus(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("required");
    }

    @Test
    void getCarrierDetailsByIdAndByMcNumberAgree() {
        CarrierDetails byId = tools.getCarrierDetails(7L, null);
        CarrierDetails byMc = tools.getCarrierDetails(null, byId.mcNumber().toLowerCase());

        assertThat(byMc).isEqualTo(byId);
        assertThat(byId.deliveredLast90Days()).isPositive();
    }

    @Test
    void badActorCarriersHaveWorseOnTimeRates() {
        double badActor = tools.getCarrierDetails(14L, null).onTimePctLast90Days();
        double regular = tools.getCarrierDetails(15L, null).onTimePctLast90Days();

        assertThat(badActor).isLessThan(regular);
    }

    @Test
    void getCarrierDetailsNeedsExactlyOneKey() {
        assertThatThrownBy(() -> tools.getCarrierDetails(null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> tools.getCarrierDetails(1L, "MC1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly one");
        assertThatThrownBy(() -> tools.getCarrierDetails(999L, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No carrier found");
    }

    @Test
    void findDelayedShipmentsDefaultsToLoadsStillOnTheRoad() {
        DelayedShipments result = tools.findDelayedShipments(null, null, null, null, null, null);

        assertThat(result.minHoursLate()).isEqualTo(2.0);
        assertThat(result.includeDelivered()).isFalse();
        assertThat(result.shipments()).isNotEmpty()
                .allSatisfy(s -> {
                    assertThat(s.status()).isEqualTo("IN_TRANSIT");
                    assertThat(s.hoursLate()).isGreaterThanOrEqualTo(2.0);
                    assertThat(s.deliveredAt()).isNull();
                })
                .isSortedAccordingTo(Comparator.comparingDouble(DelayedShipment::hoursLate).reversed());
    }

    @Test
    void findDelayedShipmentsAppliesFilters() {
        DelayedShipments all = tools.findDelayedShipments(2.0, null, null, null, true, 100);
        assertThat(all.count()).isEqualTo(100);

        DelayedShipments txOnly = tools.findDelayedShipments(2.0, null, "tx", null, true, 100);
        assertThat(txOnly.shipments()).isNotEmpty().allSatisfy(s -> assertThat(s.originState()).isEqualTo("TX"));

        DelayedShipments oneCarrier = tools.findDelayedShipments(2.0, 7L, null, null, true, 100);
        assertThat(oneCarrier.shipments()).isNotEmpty().allSatisfy(s -> assertThat(s.carrierId()).isEqualTo(7L));

        List<DelayedShipment> veryLate = tools.findDelayedShipments(30.0, null, null, null, true, 100).shipments();
        assertThat(veryLate).allSatisfy(s -> assertThat(s.hoursLate()).isGreaterThanOrEqualTo(30.0));
    }

    @Test
    void findDelayedShipmentsReportsDelayReasons() {
        DelayedShipments result = tools.findDelayedShipments(2.0, null, null, null, true, 100);

        assertThat(result.shipments()).anySatisfy(s -> assertThat(s.latestDelayReason())
                .isIn("WEATHER", "MECHANICAL", "TRAFFIC", "SHIPPER_DELAY", "HOURS_OF_SERVICE"));
    }

    @Test
    void findDelayedShipmentsValidatesInput() {
        assertThatThrownBy(() -> tools.findDelayedShipments(-1.0, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.findDelayedShipments(null, null, null, null, null, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tools.findDelayedShipments(null, null, "Texas", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("two-letter");
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
