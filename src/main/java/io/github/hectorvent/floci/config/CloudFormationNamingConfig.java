package io.github.hectorvent.floci.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "floci.cloudformation")
public interface CloudFormationNamingConfig {
    @WithDefault("false")
    boolean deterministicLambdaNames();
}
