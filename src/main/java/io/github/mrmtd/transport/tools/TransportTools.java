package io.github.mrmtd.transport.tools;

import io.github.mrmtd.transport.model.CarrierDetails;
import io.github.mrmtd.transport.model.DelayedShipment;
import io.github.mrmtd.transport.model.DelayedShipments;
import io.github.mrmtd.transport.model.LoadStatus;
import io.github.mrmtd.transport.repo.CarrierRepository;
import io.github.mrmtd.transport.repo.DelayRule;
import io.github.mrmtd.transport.repo.LoadRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * The MCP tools. Each public method annotated with {@link McpTool} is discovered by Spring AI's
 * annotation scanner and listed by the server's tools/list. The method's parameters become the
 * tool's JSON input schema and its record return type becomes the output schema.
 *
 * <p>Bad input or an unknown id throws {@link IllegalArgumentException}; the MCP server turns that
 * into a tool result with {@code isError: true}, so the calling model sees the message and can retry.
 */
@Component
public class TransportTools {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final LoadRepository loads;
    private final CarrierRepository carriers;

    public TransportTools(LoadRepository loads, CarrierRepository carriers) {
        this.loads = loads;
        this.carriers = carriers;
    }

    @McpTool(name = "getLoadStatus",
            title = "Get load status",
            description = """
                    Current status of one load: carrier, lane, scheduled vs actual pickup and delivery, \
                    hours late, and the most recent tracking event. Use the load number, e.g. LD-000420.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false),
            generateOutputSchema = true)
    public LoadStatus getLoadStatus(
            @McpToolParam(description = "Load number, e.g. LD-000420. A bare number like 420 also works.")
            String loadNumber) {
        String normalized = normalizeLoadNumber(loadNumber);
        return loads.findStatus(normalized)
                .orElseThrow(() -> new IllegalArgumentException("No load found with load number " + normalized));
    }

    @McpTool(name = "getCarrierDetails",
            title = "Get carrier details",
            description = """
                    Carrier profile (MC/DOT numbers, SCAC, contact, safety rating, active flag) plus open loads \
                    and on-time performance over the last 90 days. Give exactly one of carrierId or mcNumber.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false),
            generateOutputSchema = true)
    public CarrierDetails getCarrierDetails(
            @McpToolParam(description = "Carrier id, e.g. 7", required = false) Long carrierId,
            @McpToolParam(description = "FMCSA MC number, e.g. MC155433", required = false) String mcNumber) {
        String mc = blankToNull(mcNumber);
        if ((carrierId == null) == (mc == null)) {
            throw new IllegalArgumentException("Give exactly one of carrierId or mcNumber");
        }
        return carriers.findDetails(carrierId, mc)
                .orElseThrow(() -> new IllegalArgumentException(carrierId != null
                        ? "No carrier found with id " + carrierId
                        : "No carrier found with MC number " + mc));
    }

    @McpTool(name = "findDelayedShipments",
            title = "Find delayed shipments",
            description = """
                    Loads running late, most late first. Hours late = actual delivery (or now, while still on \
                    the road) minus scheduled delivery. By default lists only loads still in transit; set \
                    includeDelivered to also list loads that were delivered late. Each result includes the \
                    latest delay reason the driver reported, if any.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false),
            generateOutputSchema = true)
    public DelayedShipments findDelayedShipments(
            @McpToolParam(description = "Minimum hours late to include. Default 2.", required = false)
            Double minHoursLate,
            @McpToolParam(description = "Only loads for this carrier id", required = false)
            Long carrierId,
            @McpToolParam(description = "Only loads picked up in this US state, two letters, e.g. TX", required = false)
            String originState,
            @McpToolParam(description = "Only loads going to this US state, two letters, e.g. GA", required = false)
            String destinationState,
            @McpToolParam(description = "Also include loads already delivered late. Default false.", required = false)
            Boolean includeDelivered,
            @McpToolParam(description = "Maximum rows to return, 1 to 100. Default 20.", required = false)
            Integer limit) {

        double min = minHoursLate == null ? DelayRule.LATE_THRESHOLD_HOURS : minHoursLate;
        if (min < 0) {
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
