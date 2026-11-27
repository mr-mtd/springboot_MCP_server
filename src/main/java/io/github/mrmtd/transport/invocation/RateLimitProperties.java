package io.github.mrmtd.transport.invocation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Token-bucket settings, per caller (MCP session or REST client IP).
 *
 * @param enabled          turn the limiter off entirely
 * @param capacity         burst size: how many calls a caller can make back to back
 * @param refillPerSecond  sustained rate: tokens added back per second
 */
@ConfigurationProperties("transport.rate-limit")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int capacity,
        @DefaultValue("2") double refillPerSecond) {
}
