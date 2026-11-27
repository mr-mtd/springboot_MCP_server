package io.github.mrmtd.transport.tools;

import io.github.mrmtd.transport.invocation.Caller;
import io.github.mrmtd.transport.invocation.ToolInvoker;
import io.github.mrmtd.transport.model.CarrierDetails;
import io.github.mrmtd.transport.model.DelayedShipments;
import io.github.mrmtd.transport.model.LoadStatus;
import io.github.mrmtd.transport.service.TransportService;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP tools. Each public method annotated with {@link McpTool} is discovered by Spring AI's
 * annotation scanner and listed by the server's tools/list. The method's parameters become the
 * tool's JSON input schema and its record return type becomes the output schema.
 *
 * <p>The {@link McpSyncServerExchange} parameter isn't part of the schema: Spring AI injects it, and
 * it identifies the MCP session making the call. Each method hands the work to
 * {@link TransportService} through {@link ToolInvoker}, which adds the rate limit, session tracking,
 * logging and metrics.
 *
 * <p>Bad input, an unknown id, or a rate-limited call throws; the MCP server turns that into a tool
 * result with {@code isError: true}, so the calling model sees the message and can retry.
 */
@Component
public class TransportTools {

    private final TransportService service;
    private final ToolInvoker invoker;

    public TransportTools(TransportService service, ToolInvoker invoker) {
        this.service = service;
        this.invoker = invoker;
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
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Load number, e.g. LD-000420. A bare number like 420 also works.")
            String loadNumber) {
        return invoker.invoke("getLoadStatus", Caller.mcp(exchange), () -> service.getLoadStatus(loadNumber));
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
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Carrier id, e.g. 7", required = false) Long carrierId,
            @McpToolParam(description = "FMCSA MC number, e.g. MC155433", required = false) String mcNumber) {
        return invoker.invoke("getCarrierDetails", Caller.mcp(exchange),
                () -> service.getCarrierDetails(carrierId, mcNumber));
    }

    @McpTool(name = "findDelayedShipments",
            title = "Find delayed shipments",
            description = """
                    Loads running late, most late first. Hours late = actual delivery (or now, while still on \
                    the road) minus scheduled delivery; a load is late when that is more than 2 hours. By \
                    default lists only loads still in transit; set includeDelivered to also list loads that \
                    were delivered late. Each result includes the latest delay reason the driver reported, \
                    if any.""",
            annotations = @McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false),
            generateOutputSchema = true)
    public DelayedShipments findDelayedShipments(
            McpSyncServerExchange exchange,
            @McpToolParam(description = "Only loads more than this many hours late. Default 2, which is also the minimum.",
                    required = false)
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
        return invoker.invoke("findDelayedShipments", Caller.mcp(exchange),
                () -> service.findDelayedShipments(minHoursLate, carrierId, originState, destinationState,
                        includeDelivered, limit));
    }
}
