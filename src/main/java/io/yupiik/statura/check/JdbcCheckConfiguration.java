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
