package io.github.mrmtd.transport.api;

import io.github.mrmtd.transport.model.CarrierDetails;
import io.github.mrmtd.transport.model.DelayedShipments;
import io.github.mrmtd.transport.model.LoadStatus;
import io.github.mrmtd.transport.tools.TransportTools;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A plain REST mirror of the MCP tools, so they can be tried from Swagger UI (/swagger) or curl
 * without an MCP client. Every endpoint calls the same {@link TransportTools} method the MCP
 * server does, so the two can't drift apart.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Transport", description = "Read-only REST mirror of the MCP tools")
public class TransportApiController {

    private final TransportTools tools;

    public TransportApiController(TransportTools tools) {
        this.tools = tools;
    }

    @GetMapping("/loads/{loadNumber}")
    @Operation(summary = "Get load status", description = "Same as the getLoadStatus MCP tool.")
    public LoadStatus loadStatus(
            @Parameter(description = "Load number, e.g. LD-000420 or 420") @PathVariable String loadNumber) {
        return tools.getLoadStatus(loadNumber);
    }

    @GetMapping("/carriers/{carrierId}")
    @Operation(summary = "Get carrier details by id", description = "Same as the getCarrierDetails MCP tool.")
    public CarrierDetails carrierById(@Parameter(description = "Carrier id, e.g. 7") @PathVariable long carrierId) {
        return tools.getCarrierDetails(carrierId, null);
    }

    @GetMapping("/carriers")
    @Operation(summary = "Get carrier details by MC number", description = "Same as the getCarrierDetails MCP tool.")
    public CarrierDetails carrierByMcNumber(
            @Parameter(description = "FMCSA MC number, e.g. MC155433") @RequestParam String mcNumber) {
        return tools.getCarrierDetails(null, mcNumber);
    }

    @GetMapping("/shipments/delayed")
    @Operation(summary = "Find delayed shipments", description = "Same as the findDelayedShipments MCP tool.")
    public DelayedShipments delayedShipments(
            @Parameter(description = "Minimum hours late. Default 2.") @RequestParam(required = false) Double minHoursLate,
            @Parameter(description = "Only this carrier id") @RequestParam(required = false) Long carrierId,
            @Parameter(description = "Origin state, e.g. TX") @RequestParam(required = false) String originState,
            @Parameter(description = "Destination state, e.g. GA") @RequestParam(required = false) String destinationState,
            @Parameter(description = "Also include loads delivered late. Default false.")
            @RequestParam(required = false) Boolean includeDelivered,
            @Parameter(description = "1 to 100. Default 20.") @RequestParam(required = false) Integer limit) {
        return tools.findDelayedShipments(minHoursLate, carrierId, originState, destinationState,
                includeDelivered, limit);
    }
}
