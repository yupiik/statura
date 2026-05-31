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
package io.yupiik.statura.configuration;

import io.yupiik.fusion.framework.api.container.configuration.ConfigurationRegistration;
import io.yupiik.fusion.framework.api.io.ReaderSupplier;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.event.OnEvent;
import io.yupiik.fusion.json.configuration.JsonConfigurationSource;

@DefaultScoped
public class JsonConfigurationProducer {
    public void registerJsonConfiguration(@OnEvent final ConfigurationRegistration configurationRegistration) {
        final var location = configurationRegistration
                .configuration()
                .get("statura.configurationLocation")
                .orElse("statura.json");
        configurationRegistration
                .addSource()
                .accept(new JsonConfigurationSource(ReaderSupplier.from(location, "{}")) {
                    @Override
                    public String get(final String key) { // CLI to config style
                        return super.get(key.replace('-', '.'));
                    }
                });
    }
}
