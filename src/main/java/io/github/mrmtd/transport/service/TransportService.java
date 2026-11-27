package io.github.mrmtd.transport.service;

import io.github.mrmtd.transport.model.CarrierDetails;
import io.github.mrmtd.transport.model.DelayedShipment;
import io.github.mrmtd.transport.model.DelayedShipments;
import io.github.mrmtd.transport.model.LoadStatus;
import io.github.mrmtd.transport.repo.CarrierRepository;
import io.github.mrmtd.transport.repo.DelayRule;
import io.github.mrmtd.transport.repo.LoadRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * What each tool does: input validation and normalisation, then one repository call. Both the MCP
 * tools ({@code tools.TransportTools}) and the REST mirror ({@code api.TransportApiController}) call
 * these methods, so the two always return the same thing.
 *
 * <p>Bad input throws {@link IllegalArgumentException}; an unknown id throws {@link NotFoundException}.
 */
@Service
public class TransportService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final LoadRepository loads;
    private final CarrierRepository carriers;

    public TransportService(LoadRepository loads, CarrierRepository carriers) {
        this.loads = loads;
        this.carriers = carriers;
    }

    public LoadStatus getLoadStatus(String loadNumber) {
        String normalized = normalizeLoadNumber(loadNumber);
        return loads.findStatus(normalized)
                .orElseThrow(() -> new NotFoundException("No load found with load number " + normalized));
    }

    public CarrierDetails getCarrierDetails(Long carrierId, String mcNumber) {
        String mc = blankToNull(mcNumber);
        if ((carrierId == null) == (mc == null)) {
            throw new IllegalArgumentException("Give exactly one of carrierId or mcNumber");
        }
        return carriers.findDetails(carrierId, mc)
                .orElseThrow(() -> new NotFoundException(carrierId != null
                        ? "No carrier found with id " + carrierId
                        : "No carrier found with MC number " + mc));
    }

    /**
     * Late loads that are more than {@code minHoursLate} hours late. Only late loads are ever returned
     * (see {@link DelayRule}), so a {@code minHoursLate} below the 2-hour threshold acts like 2.
     */
    public DelayedShipments findDelayedShipments(Double minHoursLate, Long carrierId, String originState,
                                                 String destinationState, Boolean includeDelivered, Integer limit) {
        double min = minHoursLate == null ? DelayRule.LATE_THRESHOLD_HOURS : minHoursLate;
        if (min < 0 || Double.isNaN(min)) {
            throw new IllegalArgumentException("minHoursLate must be 0 or more");
        }
        int max = limit == null ? DEFAULT_LIMIT : limit;
        if (max < 1 || max > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        boolean delivered = Boolean.TRUE.equals(includeDelivered);

        List<DelayedShipment> rows = loads.findDelayed(min, carrierId, stateCode("originState", originState),
                stateCode("destinationState", destinationState), delivered, max);
        return new DelayedShipments(min, delivered, rows.size(), rows);
    }

    /** Accepts "LD-000420", "ld-420" or "420" and returns the canonical "LD-000420". */
    static String normalizeLoadNumber(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            throw new IllegalArgumentException("loadNumber is required");
        }
        String digits = value.toUpperCase(Locale.ROOT).replaceFirst("^LD-?", "");
        if (!digits.matches("\\d{1,6}")) {
            throw new IllegalArgumentException("loadNumber should look like LD-000420, got: " + raw);
        }
        return "LD-" + "0".repeat(6 - digits.length()) + digits;
    }

    private static String stateCode(String name, String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!upper.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException(name + " must be a two-letter state code, got: " + raw);
        }
        return upper;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
