package io.yupiik.statura;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.scanning.Bean;

import java.time.Clock;

import static java.time.Clock.systemUTC;

@ApplicationScoped
public class ClockBean {
    @Bean
    @ApplicationScoped
    public Clock clock() {
        return systemUTC();
    }
}
