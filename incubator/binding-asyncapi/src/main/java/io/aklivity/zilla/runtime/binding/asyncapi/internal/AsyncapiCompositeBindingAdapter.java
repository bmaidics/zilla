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
package io.aklivity.zilla.runtime.binding.asyncapi.internal;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.aklivity.zilla.runtime.binding.asyncapi.config.AsyncapiOptionsConfig;
import io.aklivity.zilla.runtime.binding.asyncapi.internal.model.Asyncapi;
import io.aklivity.zilla.runtime.binding.asyncapi.internal.view.AsyncapiServerView;

public class AsyncapiCompositeBindingAdapter
{
    protected static final String APPLICATION_JSON = "application/json";

    protected Asyncapi asyncApi;
    protected boolean isTlsEnabled;
    protected AsyncapiProtocol protocol;
    protected String qname;
    protected String qvault;


    protected AsyncapiProtocol resolveProtocol(
        String protocol,
        AsyncapiOptionsConfig options)
    {
        Pattern pattern = Pattern.compile("(http|mqtt)");
        Matcher matcher = pattern.matcher(protocol);
        AsyncapiProtocol asyncapiProtocol = null;
        if (matcher.find())
        {
            switch (matcher.group())
            {
            case "http":
                asyncapiProtocol = new AsyncapiHttpProtocol(qname, asyncApi, options);
                break;
            case "mqtt":
                asyncapiProtocol = new AyncapiMqttProtocol(qname, asyncApi, options);
                break;
            }
            return asyncapiProtocol;
        }
        else
        {
            // TODO: should we do something?
        }
        return asyncapiProtocol;
    }

    protected int[] resolveAllPorts()
    {
        int[] ports = new int[asyncApi.servers.size()];
        String[] keys = asyncApi.servers.keySet().toArray(String[]::new);
        for (int i = 0; i < asyncApi.servers.size(); i++)
        {
            AsyncapiServerView server = AsyncapiServerView.of(asyncApi.servers.get(keys[i]));
            URI url = server.url();
            ports[i] = url.getPort();
        }
        return ports;
    }

    protected int[] resolvePortsForScheme(
        String scheme)
    {
        requireNonNull(scheme);
        int[] ports = null;
        URI url = findFirstServerUrlWithScheme(scheme);
        if (url != null)
        {
            ports = new int[] {url.getPort()};
        }
        return ports;
    }

    protected URI findFirstServerUrlWithScheme(
        String scheme)
    {
        requireNonNull(scheme);
        URI result = null;
        for (String key : asyncApi.servers.keySet())
        {
            AsyncapiServerView server = AsyncapiServerView.of(asyncApi.servers.get(key));
            if (scheme.equals(server.url().getScheme()))
            {
                result = server.url();
                break;
            }
        }
        return result;
    }
}
