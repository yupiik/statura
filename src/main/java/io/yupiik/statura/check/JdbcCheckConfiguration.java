package io.yupiik.statura.check;

import io.yupiik.fusion.framework.build.api.configuration.Property;

public record JdbcCheckConfiguration(
        @Property(documentation = "JDBC URL.")
        String url,

        @Property(documentation = "JDBC driver (if needed, recent drivers can be loaded from the classpath).")
        String driver,

        @Property(documentation = "JDBC username.")
        String username,

        @Property(documentation = "JDBC password, prefer using secret injection for this.")
        String password,

        @Property(value = "validation-query", documentation = "SQL query to validate the connection.", defaultValue = "\"SELECT 1\"")
        String validationQuery,

        @Property(documentation = "Custom (java duration formatted) timeout for this check.", defaultValue = "\"PT30S\"")
        String timeout
) {
}
