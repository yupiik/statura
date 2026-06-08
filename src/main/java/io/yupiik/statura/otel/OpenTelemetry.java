/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.statura.otel;

import io.yupiik.fusion.framework.build.api.configuration.Property;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

public record OpenTelemetry(
        @Property(documentation = "OpenTelemetry collector HTTP endpoint for metrics.")
        String endpoint,

        @Property(documentation = "Headers to send to respect OpenTelemetry collector endpoint.", defaultValue = "java.util.Map.of()")
        Map<String, String> headers,

        @Property(documentation = "Serialization protocol: JSON or PROTOBUF.", defaultValue = "io.yupiik.statura.otel.OpenTelemetry.Protocol.JSON")
        Protocol protocol,

        @Property(documentation = "HTTP protocol version for the collector connection.", defaultValue = "java.net.http.HttpClient.Version.HTTP_1_1")
        HttpClient.Version httpVersion,

        @Property(value = "ssl-certificates", documentation = "Trusted CA certificates in PEM format for custom SSL trust.")
        List<String> sslCertificates,

        @Property(value = "ssl-client-certificate", documentation = "Client certificate in PEM format for mTLS.")
        String sslClientCertificate,

        @Property(value = "ssl-client-private-key", documentation = "Client private key in PEM format (PKCS#8) for mTLS.")
        String sslClientPrivateKey,

        @Property(value = "resource-attributes", documentation = "Resource attributes (e.g. service.name, service.version).", defaultValue = "java.util.Map.of(\"service.name\", \"statura\", \"service.version\", \"1.0\")")
        Map<String, String> resourceAttributes,

        @Property(value = "scope-attributes", documentation = "Instrumentation scope attributes (e.g. name, version).", defaultValue = "java.util.Map.of(\"name\", \"statura\", \"version\", \"1.0\")")
        Map<String, String> scopeAttributes,

        @Property(value = "flatten-attributes", documentation = "Should resource and/or scope attributes be merged with metrics attributes. Mainly useful when prometheus is not configured to handle global attributes.", defaultValue = "io.yupiik.statura.otel.OpenTelemetry.AttributeFlattening.NONE")
        AttributeFlattening flattenAttributes
) {
    public enum Protocol { JSON, PROTOBUF }
    public enum AttributeFlattening { NONE, ALL, RESOURCE, SCOPE }
}
