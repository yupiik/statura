package io.yupiik.statura.check;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record ConnectionCheckConfiguration(
        @Property(documentation = "Host to connect to.")
        String host,

        @Property(documentation = "Port to connect to.")
        int port,

        @Property(documentation = "Custom (java duration formatted) timeout for this check.", defaultValue = "\"PT10S\"")
        String timeout,

        @Property(documentation = "Optional proxy configuration.")
        Proxy proxy
) {
    public record Proxy(
            @Property(documentation = "Proxy type: HTTP, SOCKS (SOCKS5), SOCKS4 (or SOCKS4a), or DIRECT.", defaultValue = "\"DIRECT\"")
            String type,

            @Property(documentation = "Proxy host.")
            String host,

            @Property(documentation = "Proxy port.")
            int port
    ) {
    }
}
