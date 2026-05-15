package io.yupiik.statura.check;


import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

public record HttpCheckConfiguration(
        @Property(documentation = "URL to call.")
        String url,

        @Property(documentation = "HTTP protocol version.", defaultValue = "java.net.http.HttpClient.Version.HTTP_1_1")
        HttpClient.Version version,

        @Property(documentation = "HTTP method to use.", defaultValue = "\"GET\"")
        String method,

        @Property(documentation = "HTTP headers to set.", defaultValue = "java.util.Map.of()")
        Map<String, String> headers,

        @Property(documentation = "For _write_ methods, the payload to send.", defaultValue = "\"\"")
        String body,

        @Property(documentation = "Custom (java duration formatted) timeout for this check.", defaultValue = "\"PT30S\"")
        String timeout,

        @Property(value = "expected-status", documentation = "Expected response status.", defaultValue = "200")
        int expectedStatus,

        @Property(documentation = "For JSON responses, some validations on the response payload..", defaultValue = "java.util.List.of()")
        List<Assertion> assertions
) {
    public record Assertion(
            @Property(value = "json-pointer", documentation = "JSON-Pointer to extract.", defaultValue = "\"/\"")
            String jsonPointer,

            @Property(documentation = "Expected value", defaultValue = "io.yupiik.statura.check.HttpCheckConfiguration.AssertionOperator.EXISTS")
            AssertionOperator operator,

            @Property(value = "expected-value", documentation = "Expected value", defaultValue = "\"true\"")
            String expectedValue) {
    }

    public enum AssertionOperator {
        EXISTS,
        EQUALS,
        GTE,
        LTE,
        GT,
        LT
    }
}
