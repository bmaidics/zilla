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
package io.aklivity.zilla.specs.binding.sse.streams.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.rules.RuleChain.outerRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import io.aklivity.k3po.runtime.junit.annotation.Specification;
import io.aklivity.k3po.runtime.junit.rules.K3poRule;

public class ErrorIT
{
    private final K3poRule k3po = new K3poRule()
        .addScriptRoot("app", "io/aklivity/zilla/specs/binding/sse/streams/application/error");

    private final TestRule timeout = new DisableOnDebug(new Timeout(5, SECONDS));

    @Rule
    public final TestRule chain = outerRule(k3po).around(timeout);

    @Test
    @Specification({
        "${app}/server.reset/client",
        "${app}/server.reset/server" })
    public void shouldResetRequest() throws Exception
    {
        k3po.finish();
    }

    @Test
    @Specification({
        "${app}/client.abort/client",
        "${app}/client.abort/server" })
    public void shouldAbortRequest() throws Exception
    {
        k3po.finish();
    }

    @Test
    @Specification({
        "${app}/server.abort/client",
        "${app}/server.abort/server" })
    public void shouldAbortResponse() throws Exception
    {
        k3po.finish();
    }

    @Test
    @Specification({
        "${app}/client.reset/client",
        "${app}/client.reset/server" })
    public void shouldResetResponse() throws Exception
    {
        k3po.finish();
    }
}
