/*
 * Copyright 2021-2022 Aklivity Inc.
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
package io.aklivity.zilla.runtime.engine.internal;

import static io.aklivity.zilla.runtime.engine.EngineConfiguration.ENGINE_DRAIN_ON_CLOSE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.rules.RuleChain.outerRule;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;

import io.aklivity.zilla.runtime.engine.test.EngineRule;
import io.aklivity.zilla.runtime.engine.test.annotation.Configuration;


public class ReconfigureIT
{
    private final K3poRule k3po = new K3poRule()
        .addScriptRoot("net", "io/aklivity/zilla/specs/engine/streams/network")
        .addScriptRoot("app", "io/aklivity/zilla/specs/engine/streams/application");

    private final TestRule timeout = new DisableOnDebug(new Timeout(10, SECONDS));

    private final EngineRule engine = new EngineRule()
        .directory("target/zilla-itests")
        .commandBufferCapacity(1024)
        .responseBufferCapacity(1024)
        .counterValuesBufferCapacity(8192)
        .configure(ENGINE_DRAIN_ON_CLOSE, false)
        .configurationRoot("io/aklivity/zilla/runtime/engine/internal")
        .external("app0")
        .external("app1")
        .clean();

    @Rule
    public final TestRule chain = outerRule(engine).around(k3po).around(timeout);

    private final String packageName = ReconfigureIT.class.getPackageName();
    private Path configDir = Paths.get("target/test-classes", packageName.replace(".", "/"));

    @After
    public void cleanupFileSystem() throws Exception
    {
        InputStream source = ReconfigureIT.class.getResourceAsStream("zilla.reconfigure.original.json");
        Path target = configDir.resolve("zilla.reconfigure.json");
        Files.copy(source, target, REPLACE_EXISTING);
        configDir.resolve("zilla.reconfigure.missing.json").toFile().delete();
    }

    @Test
    @Configuration("zilla.reconfigure.json")
    @Specification({
        "${app}/client.sent.data.reconfigure.modify/server",
        "${net}/client.sent.data.reconfigure.modify/client"
    })
    public void shouldReconfigureWhenModified() throws Exception
    {
        k3po.start();

        CountDownLatch latch = new CountDownLatch(1);
        InputStream source = ReconfigureIT.class.getResourceAsStream("zilla.reconfigure.after.json");
        Path target = configDir.resolve("zilla.reconfigure.json");
        Files.copy(source, target, REPLACE_EXISTING);
        Thread.sleep(2000);

        k3po.notifyBarrier("CONFIG_CHANGED");

        k3po.finish();
    }


    @Test
    @Configuration("zilla.reconfigure.missing.json")
    @Specification({
        "${app}/client.sent.data.reconfigure.create/server",
        "${net}/client.sent.data.reconfigure.create/client"
    })
    public void shouldReconfigureWhenCreated() throws Exception
    {
        k3po.start();

        InputStream source = ReconfigureIT.class.getResourceAsStream("zilla.reconfigure.original.json");
        Path target = configDir.resolve("zilla.reconfigure.missing.json");
        Files.copy(source, target);
        Thread.sleep(2000);

        k3po.notifyBarrier("CONFIG_CREATED");

        k3po.finish();
    }

    @Test
    @Configuration("zilla.reconfigure.json")
    @Specification({
        "${app}/client.sent.data.reconfigure.delete/server",
        "${net}/client.sent.data.reconfigure.delete/client"
    })
    public void shouldReconfigureWhenDeleted() throws Exception
    {
        k3po.start();
        configDir.resolve("zilla.reconfigure.json").toFile().delete();
        Thread.sleep(2000);

        k3po.notifyBarrier("CONFIG_DELETED");

        k3po.finish();
    }
}
