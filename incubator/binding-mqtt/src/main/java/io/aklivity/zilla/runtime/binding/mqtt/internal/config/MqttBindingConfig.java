/*
 * Copyright 2021-2023 Aklivity Inc.
 *
 * Aklivity licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.aklivity.zilla.runtime.binding.mqtt.internal.config;

import static io.aklivity.zilla.runtime.binding.mqtt.internal.config.MqttAuthorizationConfig.AUTHORIZATION_USERNAME_NAME;
import static io.aklivity.zilla.runtime.binding.mqtt.internal.config.MqttAuthorizationConfig.DEFAULT_CREDENTIALS;
import static java.util.stream.Collectors.toList;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.aklivity.zilla.runtime.binding.mqtt.internal.config.MqttAuthorizationConfig.MqttCredentialsConfig;
import io.aklivity.zilla.runtime.binding.mqtt.internal.config.MqttAuthorizationConfig.MqttPatternConfig;
import io.aklivity.zilla.runtime.binding.mqtt.internal.types.MqttCapabilities;
import io.aklivity.zilla.runtime.engine.config.BindingConfig;
import io.aklivity.zilla.runtime.engine.config.KindConfig;

public final class MqttBindingConfig
{
    public final long id;
    public final String name;
    public final KindConfig kind;
    public final MqttOptionsConfig options;
    public final List<MqttRouteConfig> routes;
    public final BiFunction<String, String, String> credentials;
    public final ToLongFunction<String> resolveId;

    public MqttBindingConfig(
        BindingConfig binding)
    {
        this.id = binding.id;
        this.name = binding.name;
        this.kind = binding.kind;
        this.routes = binding.routes.stream().map(MqttRouteConfig::new).collect(toList());
        this.options = (MqttOptionsConfig) binding.options;
        this.resolveId = binding.resolveId;
        this.credentials = options != null && options.authorization != null ?
            asAccessor(options.authorization.credentials) : DEFAULT_CREDENTIALS;
    }

    public MqttRouteConfig resolve(
        long authorization)
    {
        return routes.stream()
            .filter(r -> r.authorized(authorization))
            .findFirst()
            .orElse(null);
    }

    public MqttRouteConfig resolve(
        long authorization,
        MqttCapabilities capabilities)
    {
        return routes.stream()
            .filter(r -> r.authorized(authorization) && r.matches(capabilities))
            .findFirst()
            .orElse(null);
    }

    public MqttRouteConfig resolve(
        long authorization,
        String topic,
        MqttCapabilities capabilities)
    {
        return routes.stream()
            .filter(r -> r.authorized(authorization) && r.matches(topic, capabilities))
            .findFirst()
            .orElse(null);
    }

    public BiFunction<String, String, String> credentials()
    {
        return credentials;
    }

    private BiFunction<String, String, String> asAccessor(
        MqttCredentialsConfig credentials)
    {
        BiFunction<String, String, String> accessor = DEFAULT_CREDENTIALS;
        List<MqttPatternConfig> connectPatterns = credentials.connect;

        if (connectPatterns != null && !connectPatterns.isEmpty())
        {
            MqttPatternConfig config = connectPatterns.get(0);
            String name = config.name;

            Matcher connectMatch =
                Pattern.compile(config.pattern.replace("{credentials}", "(?<credentials>[^\\s]+)"))
                    .matcher("");

            accessor = orElseIfNull(accessor, (username, password) ->
            {
                String connect = name.equals(AUTHORIZATION_USERNAME_NAME) ? username : password;
                String result = null;
                if (connect != null && connectMatch.reset(connect).matches())
                {
                    result = connectMatch.group("credentials");
                }
                return result;
            });
        }

        return accessor;
    }

    private static BiFunction<String, String, String> orElseIfNull(
        BiFunction<String, String, String> first,
        BiFunction<String, String, String> second)
    {
        return (x, y) ->
        {
            String result = first.apply(x, y);
            return result != null ? result : second.apply(x, y);
        };
    }
}
