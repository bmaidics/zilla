/*
 * Copyright 2021-2023 Aklivity Inc
 *
 * Licensed under the Aklivity Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 *   https://www.aklivity.io/aklivity-community-license/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.aklivity.zilla.runtime.binding.asyncapi.internal.config;

import static io.aklivity.zilla.runtime.engine.config.KindConfig.SERVER;
import static java.util.Collections.emptyList;

import java.util.List;
import java.util.Map;

import io.aklivity.zilla.runtime.binding.asyncapi.config.AsyncapiOptionsConfig;
import io.aklivity.zilla.runtime.binding.asyncapi.internal.model.Asyncapi;
import io.aklivity.zilla.runtime.binding.asyncapi.internal.model.AsyncapiOperation;
import io.aklivity.zilla.runtime.binding.asyncapi.internal.view.AsyncapiServerView;
import io.aklivity.zilla.runtime.binding.tcp.config.TcpConditionConfig;
import io.aklivity.zilla.runtime.binding.tcp.config.TcpOptionsConfig;
import io.aklivity.zilla.runtime.binding.tls.config.TlsOptionsConfig;
import io.aklivity.zilla.runtime.engine.config.BindingConfig;
import io.aklivity.zilla.runtime.engine.config.BindingConfigBuilder;
import io.aklivity.zilla.runtime.engine.config.MetricRefConfig;
import io.aklivity.zilla.runtime.engine.config.NamespaceConfig;
import io.aklivity.zilla.runtime.engine.config.NamespaceConfigBuilder;
import io.aklivity.zilla.runtime.engine.config.RouteConfigBuilder;

public class AsyncapiServerNamespaceGenerator extends AsyncapiNamespaceGenerator
{
    public NamespaceConfig generate(
        BindingConfig binding,
        AsyncapiNamespaceConfig namespaceConfig)
    {
        List<AsyncapiServerView> servers = namespaceConfig.servers;
        AsyncapiOptionsConfig options = binding.options != null ? (AsyncapiOptionsConfig) binding.options : EMPTY_OPTION;
        final List<MetricRefConfig> metricRefs = binding.telemetryRef != null ?
            binding.telemetryRef.metricRefs : emptyList();


        final String namespace = String.join("+", namespaceConfig.asyncapiLabels);
        return NamespaceConfig.builder()
                .name(String.format("%s/%s", qname, namespace))
                .inject(n -> this.injectNamespaceMetric(n, !metricRefs.isEmpty()))
                .inject(n -> this.injectCatalog(n, namespaceConfig.asyncapis))
                .inject(n -> injectTcpServer(n, servers, options, metricRefs))
                .inject(n -> injectTlsServer(n, options))
                .inject(n -> injectProtocolServers(n, servers, metricRefs))
                .build();
    }

    private <C> NamespaceConfigBuilder<C> injectProtocolServers(
        NamespaceConfigBuilder<C> namespace,
        List<AsyncapiServerView> servers,
        List<MetricRefConfig> metricRefs)
    {
        for (AsyncapiServerView server : servers)
        {
            final AsyncapiProtocol protocol = server.getAsyncapiProtocol();
            namespace = namespace
                .binding()
                    .name(String.format("%s_server0", protocol.scheme))
                    .type(protocol.scheme)
                    .inject(b -> this.injectMetrics(b, metricRefs))
                    .kind(SERVER)
                    .inject(protocol::injectProtocolServerOptions)
                    .inject(protocol::injectProtocolServerRoutes)
                .build();
        }
        return  namespace;
    }

    private <C> NamespaceConfigBuilder<C> injectTcpServer(
        NamespaceConfigBuilder<C> namespace,
        List<AsyncapiServerView> servers,
        AsyncapiOptionsConfig options,
        List<MetricRefConfig> metricRefs)
    {
        int[] allPorts = resolveAllPorts(servers);
        int[] compositePorts = resolvePorts(servers, false);
        int[] compositeSecurePorts = resolvePorts(servers, true);

        this.isTlsEnabled =  compositeSecurePorts.length > 0;

        final TcpOptionsConfig tcpOption = options.tcp != null ? options.tcp :
            TcpOptionsConfig.builder()
                .host("0.0.0.0")
                .ports(allPorts)
                .build();

        namespace
            .binding()
                .name("tcp_server0")
                .type("tcp")
                .kind(SERVER)
                .inject(b -> this.injectMetrics(b, metricRefs))
                .options(tcpOption)
                .inject(b -> this.injectPlainTcpRoute(b, compositePorts))
                .inject(b -> this.injectTlsTcpRoute(b, compositeSecurePorts, metricRefs))
                .build();

        return namespace;
    }

    protected <C> BindingConfigBuilder<C> injectPlainTcpRoute(
        BindingConfigBuilder<C> binding,
        List<AsyncapiServerView> servers)
    {
        for (AsyncapiServerView server : servers)
        {
            final RouteConfigBuilder<BindingConfigBuilder<C>> routeBuilder = binding.route();
            final AsyncapiProtocol protocol = server.getAsyncapiProtocol();
            final int[] compositePorts = new int[] { server.getPort() };
            binding = routeBuilder
                .when(TcpConditionConfig::builder)
                    .ports(compositePorts)
                    .build()
                    .exit(String.format("%s_server0", protocol.scheme))
                    .build();
        }
        return binding;
    }

    private <C> BindingConfigBuilder<C> injectTlsTcpRoute(
        BindingConfigBuilder<C> binding,
        int[] compositeSecurePorts,
        List<MetricRefConfig> metricRefs)
    {
        if (isTlsEnabled)
        {
            binding
                .inject(b -> this.injectMetrics(b, metricRefs))
                .route()
                    .when(TcpConditionConfig::builder)
                        .ports(compositeSecurePorts)
                        .build()
                    .exit("tls_server0")
                    .build();
        }
        return binding;
    }

    private <C> NamespaceConfigBuilder<C> injectTlsServer(
        NamespaceConfigBuilder<C> namespace,
        AsyncapiOptionsConfig options)
    {
        if (isTlsEnabled)
        {
            namespace
                .binding()
                    .name("tls_server0")
                    .type("tls")
                    .kind(SERVER)
                    .options(TlsOptionsConfig::builder)
                        .keys(options.tls.keys)
                        .sni(options.tls.sni)
                        .alpn(options.tls.alpn)
                        .build()
                    .vault(String.format("%s:%s", this.namespace, vault))
                    .exit(String.format("%s_server0", protocol.scheme))
                    .build();
        }
        return namespace;
    }
}
