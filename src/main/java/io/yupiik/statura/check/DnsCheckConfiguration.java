package io.yupiik.statura.check;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.util.List;

public record DnsCheckConfiguration(
        @Property(documentation = "Hostname to resolve.")
        String hostname,

        @Property(documentation = "Custom (java duration formatted) timeout for this check.", defaultValue = "\"PT10S\"")
        String timeout,

        @Property(documentation = "Optional list of expected resolved IP addresses.")
        List<String> expectedAddresses
) {
}
