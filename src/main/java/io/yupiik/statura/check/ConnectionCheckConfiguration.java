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
