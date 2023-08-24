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
package io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.stream;

import static io.aklivity.zilla.runtime.engine.buffer.BufferPool.NO_SLOT;
import static io.aklivity.zilla.runtime.engine.concurrent.Signaler.NO_CANCEL_ID;
import static java.lang.System.currentTimeMillis;
import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.Object2LongHashMap;
import org.agrona.collections.Object2ObjectHashMap;
import org.agrona.collections.ObjectHashSet;
import org.agrona.concurrent.UnsafeBuffer;

import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.InstanceId;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.MqttKafkaConfiguration;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.config.MqttKafkaBindingConfig;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.config.MqttKafkaHeaderHelper;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.config.MqttKafkaRouteConfig;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.Array32FW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.Flyweight;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.KafkaAckMode;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.KafkaCapabilities;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.KafkaEvaluation;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.KafkaHeaderFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.KafkaKeyFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.KafkaOffsetType;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttMessageFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttPayloadFormat;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttPayloadFormatFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttPublishFlags;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttSessionFlags;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttSessionStateFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttWillDeliverAt;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.MqttWillSignalFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.OctetsFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.String16FW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.AbortFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.BeginFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.DataFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.EndFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.ExtensionFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.FlushFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.KafkaBeginExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.KafkaDataExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.KafkaGroupDataExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.KafkaMergedDataExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.MqttBeginExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.MqttDataExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.MqttSessionBeginExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.MqttSessionDataExFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.ResetFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.SignalFW;
import io.aklivity.zilla.runtime.binding.mqtt.kafka.internal.types.stream.WindowFW;
import io.aklivity.zilla.runtime.engine.EngineContext;
import io.aklivity.zilla.runtime.engine.binding.BindingHandler;
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer;
import io.aklivity.zilla.runtime.engine.buffer.BufferPool;
import io.aklivity.zilla.runtime.engine.concurrent.Signaler;

public class MqttKafkaSessionFactory implements MqttKafkaStreamFactory
{
    private static final byte SLASH_BYTE = (byte) '/';
    private static final KafkaAckMode KAFKA_DEFAULT_ACK_MODE = KafkaAckMode.LEADER_ONLY;
    private static final String KAFKA_TYPE_NAME = "kafka";
    private static final String MQTT_TYPE_NAME = "mqtt";
    private static final String MIGRATE_KEY_POSTFIX = "#migrate";
    private static final String WILL_SIGNAL_KEY_POSTFIX = "#will-signal";
    private static final String WILL_KEY_POSTFIX = "#will-";
    private static final String GROUP_PROTOCOL = "highlander";
    private static final String16FW SENDER_ID_NAME = new String16FW("sender-id");
    private static final String16FW TYPE_HEADER_NAME = new String16FW("type");
    private static final String16FW WILL_SIGNAL_NAME = new String16FW("will-signal");
    private static final OctetsFW EMPTY_OCTETS = new OctetsFW().wrap(new UnsafeBuffer(new byte[0]), 0, 0);
    private static final int DATA_FLAG_COMPLETE = 0x03;
    public static final String MQTT_CLIENTS_GROUP_ID = "mqtt-clients";
    private static final int SIGNAL_WILL_DELIVER = 1;
    private static final int SIGNAL_INITIATE_WILL_KAFKA_STREAM = 2;

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();
    private final FlushFW flushRO = new FlushFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final FlushFW.Builder flushRW = new FlushFW.Builder();
    private final MqttMessageFW.Builder mqttMessageRW = new MqttMessageFW.Builder();
    private final MqttWillSignalFW.Builder mqttWillSignalRW = new MqttWillSignalFW.Builder();
    private final Array32FW.Builder<KafkaHeaderFW.Builder, KafkaHeaderFW> kafkaHeadersRW =
        new Array32FW.Builder<>(new KafkaHeaderFW.Builder(), new KafkaHeaderFW());

    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();
    private final SignalFW signalRO = new SignalFW();

    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();

    private final ExtensionFW extensionRO = new ExtensionFW();
    private final MqttBeginExFW mqttBeginExRO = new MqttBeginExFW();
    private final MqttSessionStateFW mqttSessionStateRO = new MqttSessionStateFW();
    private final MqttWillSignalFW mqttWillSignalRO = new MqttWillSignalFW();
    private final MqttMessageFW mqttWillRO = new MqttMessageFW();
    private final MqttDataExFW mqttDataExRO = new MqttDataExFW();
    private final KafkaDataExFW kafkaDataExRO = new KafkaDataExFW();
    private final KafkaBeginExFW.Builder kafkaBeginExRW = new KafkaBeginExFW.Builder();
    private final KafkaDataExFW.Builder kafkaDataExRW = new KafkaDataExFW.Builder();
    private final MutableDirectBuffer writeBuffer;
    private final MutableDirectBuffer extBuffer;
    private final MutableDirectBuffer kafkaHeadersBuffer;
    private final MutableDirectBuffer willMessageBuffer;
    private final MutableDirectBuffer willSignalBuffer;
    private final BufferPool bufferPool;
    private final BindingHandler streamFactory;
    private final Signaler signaler;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;
    private final int kafkaTypeId;
    private final int mqttTypeId;
    private final LongFunction<MqttKafkaBindingConfig> supplyBinding;
    private final Supplier<String> supplySessionId;
    private final Supplier<String> supplyWillId;
    private final Supplier<String> supplyLifetimeId;
    private final Supplier<Long> supplyTime;
    private final Long2ObjectHashMap<String> sessionIds;
    private final MqttKafkaHeaderHelper helper;
    private final String16FW binaryFormat;
    private final String16FW textFormat;
    private final int coreIndex;
    private final Supplier<Long> supplyTraceId;
    private final Object2LongHashMap<String16FW> willDeliverIds;
    private final InstanceId instanceId;
    private KafkaWillProxy willProxy;
    private long reconnectAt = NO_CANCEL_ID;

    public MqttKafkaSessionFactory(
        MqttKafkaConfiguration config,
        EngineContext context,
        InstanceId instanceId,
        LongFunction<MqttKafkaBindingConfig> supplyBinding)
    {
        this.kafkaTypeId = context.supplyTypeId(KAFKA_TYPE_NAME);
        this.mqttTypeId = context.supplyTypeId(MQTT_TYPE_NAME);
        this.writeBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.extBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.kafkaHeadersBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.willMessageBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.willSignalBuffer = new UnsafeBuffer(new byte[context.writeBuffer().capacity()]);
        this.bufferPool = context.bufferPool();
        this.helper = new MqttKafkaHeaderHelper();
        this.streamFactory = context.streamFactory();
        this.signaler = context.signaler();
        this.supplyInitialId = context::supplyInitialId;
        this.supplyReplyId = context::supplyReplyId;
        this.supplyBinding = supplyBinding;
        this.supplySessionId = config.sessionIdSupplier();
        this.supplyWillId = config.willIdSupplier();
        this.supplyLifetimeId = config.lifetimeIdSupplier();
        this.supplyTime = config.timeSupplier();
        this.supplyTraceId = context::supplyTraceId;
        this.sessionIds = new Long2ObjectHashMap<>();
        this.binaryFormat = new String16FW(MqttPayloadFormat.BINARY.name());
        this.textFormat = new String16FW(MqttPayloadFormat.TEXT.name());
        this.coreIndex = context.index();
        this.willDeliverIds = new Object2LongHashMap<>(-1);
        this.instanceId = instanceId;
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer mqtt)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long originId = begin.originId();
        final long routedId = begin.routedId();
        final long initialId = begin.streamId();
        final long authorization = begin.authorization();

        final MqttKafkaBindingConfig binding = supplyBinding.apply(routedId);

        final MqttKafkaRouteConfig resolved = binding != null ? binding.resolve(authorization) : null;

        MessageConsumer newStream = null;

        if (resolved != null)
        {
            final long resolvedId = resolved.id;
            final String16FW sessionTopic = binding.sessionsTopic();
            newStream = new MqttSessionProxy(mqtt, originId, routedId, initialId, resolvedId,
                binding.id, sessionTopic)::onMqttMessage;
        }

        return newStream;
    }

    @Override
    public void onAttached(
        long bindingId)
    {
        MqttKafkaBindingConfig binding = supplyBinding.apply(bindingId);
        if (coreIndex == 0)
        {

            Optional<MqttKafkaRouteConfig> route = binding.routes.stream().findFirst();
            final long routeId = route.map(mqttKafkaRouteConfig -> mqttKafkaRouteConfig.id).orElse(0L);

            willProxy = new KafkaWillProxy(binding.id, routeId,
                binding.sessionsTopic(), binding.messagesTopic(), binding.retainedTopic());

            this.reconnectAt = signaler.signalAt(
                currentTimeMillis(),
                SIGNAL_INITIATE_WILL_KAFKA_STREAM,
                this::onWillKafkaStreamInitializationSignal);
        }
        sessionIds.put(bindingId, supplySessionId.get());
    }

    private void onWillKafkaStreamInitializationSignal(
        int signalId)
    {
        assert signalId == SIGNAL_INITIATE_WILL_KAFKA_STREAM;

        this.reconnectAt = NO_CANCEL_ID;

        willProxy.doKafkaBegin(supplyTraceId.get(), 0, 0);
    }

    @Override
    public void onDetached(
        long bindingId)
    {
        sessionIds.remove(bindingId);

        if (willProxy != null)
        {
            willProxy.doKafkaEnd(supplyTraceId.get(), 0);
            signaler.cancel(reconnectAt);
            reconnectAt = NO_CANCEL_ID;
        }
    }

    private final class MqttSessionProxy
    {
        private final MessageConsumer mqtt;
        private final long originId;
        private final long routedId;
        private final long initialId;
        private final long replyId;
        private final String16FW sessionId;
        private final String16FW sessionsTopic;
        public String lifetimeId;
        private KafkaSessionProxy session;
        private KafkaGroupProxy group;
        private int state;

        private long initialSeq;
        private long initialAck;
        private int initialMax;

        private long replySeq;
        private long replyAck;
        private int replyMax;
        private int replyPad;

        private String16FW clientId;
        private String16FW clientIdMigrate;
        private int sessionExpiryMillis;
        private int flags;
        private int willPadding;
        private int willSignalSize;
        private String willId;
        private int delay;

        private MqttSessionProxy(
            MessageConsumer mqtt,
            long originId,
            long routedId,
            long initialId,
            long resolvedId,
            long bindingId,
            String16FW sessionsTopic)
        {
            this.mqtt = mqtt;
            this.originId = originId;
            this.routedId = routedId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.session = new KafkaFetchWillSignalProxy(originId, resolvedId, this);
            this.sessionsTopic = sessionsTopic;
            this.sessionId = new String16FW(sessionIds.get(bindingId));
        }

        private void onMqttMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onMqttBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onMqttData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onMqttEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onMqttAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onMqttReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onMqttWindow(window);
                break;
            }
        }

        private void onMqttBegin(
            BeginFW begin)
        {
            final long sequence = begin.sequence();
            final long acknowledge = begin.acknowledge();
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            assert acknowledge <= sequence;
            assert sequence >= initialSeq;
            assert acknowledge >= initialAck;

            initialSeq = sequence;
            initialAck = acknowledge;
            state = MqttKafkaState.openingInitial(state);

            assert initialAck <= initialSeq;

            final OctetsFW extension = begin.extension();
            final MqttBeginExFW mqttBeginEx = extension.get(mqttBeginExRO::tryWrap);

            assert mqttBeginEx.kind() == MqttBeginExFW.KIND_SESSION;
            final MqttSessionBeginExFW mqttSessionBeginEx = mqttBeginEx.session();

            final String clientId0 = mqttSessionBeginEx.clientId().asString();
            this.clientId = new String16FW(clientId0);
            this.clientIdMigrate = new String16FW(clientId0 + MIGRATE_KEY_POSTFIX);

            final int sessionExpiry = mqttSessionBeginEx.expiry();
            sessionExpiryMillis = mqttSessionBeginEx.expiry() == 0 ? Integer.MAX_VALUE : (int) SECONDS.toMillis(sessionExpiry);
            flags = mqttSessionBeginEx.flags();

            if (!isSetWillFlag(flags) || isCleanStart(flags))
            {
                final long routedId = session.routedId;
                session = new KafkaSessionSignalProxy(originId, routedId, this);
            }

            session.doKafkaBeginIfNecessary(traceId, authorization, affinity);
        }

        private void onMqttData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();
            final int flags = data.flags();
            final OctetsFW extension = data.extension();
            final OctetsFW payload = data.payload();

            assert acknowledge <= sequence;
            assert sequence >= initialSeq;

            initialSeq = sequence;

            assert initialAck <= initialSeq;

            final DirectBuffer buffer = payload.buffer();
            final int offset = payload.offset();
            final int limit = payload.limit();


            final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
            final MqttDataExFW mqttDataEx =
                dataEx != null && dataEx.typeId() == mqttTypeId ? extension.get(mqttDataExRO::tryWrap) : null;
            final MqttSessionDataExFW mqttSessionDataEx =
                mqttDataEx != null && mqttDataEx.kind() == MqttDataExFW.KIND_SESSION ? mqttDataEx.session() : null;

            Flyweight kafkaDataEx;
            Flyweight kafkaPayload;
            if (mqttSessionDataEx != null)
            {
                switch (mqttSessionDataEx.kind().get())
                {
                case WILL:
                    if (lifetimeId == null)
                    {
                        lifetimeId = supplyLifetimeId.get();
                    }
                    this.willId  = supplyWillId.get();
                    willPadding = lifetimeId.length() + willId.length();

                    String16FW key = new String16FW(clientId.asString() + WILL_KEY_POSTFIX + lifetimeId);
                    kafkaDataEx = kafkaDataExRW
                        .wrap(extBuffer, 0, extBuffer.capacity())
                        .typeId(kafkaTypeId)
                        .merged(m -> m
                            .deferred(0)
                            .timestamp(now().toEpochMilli())
                            .partition(p -> p.partitionId(-1).partitionOffset(-1))
                            .key(b -> b.length(key.length())
                                .value(key.value(), 0, key.length()))
                            .hashKey(b -> b.length(clientId.length())
                                .value(clientId.value(), 0, clientId.length())))
                        .build();

                    MqttMessageFW will = mqttWillRO.tryWrap(buffer, offset, limit);
                    this.delay = (int) Math.min(SECONDS.toMillis(will.delay()), sessionExpiryMillis);
                    final MqttMessageFW.Builder willMessageBuilder =
                        mqttMessageRW.wrap(willMessageBuffer, 0, willMessageBuffer.capacity())
                            .topic(will.topic())
                            .delay(delay)
                            .qos(will.qos())
                            .flags(will.flags())
                            .expiryInterval(will.expiryInterval())
                            .contentType(will.contentType())
                            .format(will.format())
                            .responseTopic(will.responseTopic())
                            .lifetimeId(lifetimeId)
                            .willId(willId)
                            .correlation(will.correlation())
                            .properties(will.properties())
                            .payload(will.payload());

                    kafkaPayload = willMessageBuilder.build();
                    session.doKafkaData(traceId, authorization, budgetId,
                        kafkaPayload.sizeof(), flags, kafkaPayload, kafkaDataEx);

                    String16FW willSignalKey = new String16FW(clientId.asString() + WILL_SIGNAL_KEY_POSTFIX);
                    Flyweight willSignalKafkaDataEx = kafkaDataExRW
                        .wrap(extBuffer, 0, extBuffer.capacity())
                        .typeId(kafkaTypeId)
                        .merged(m -> m
                            .deferred(0)
                            .timestamp(now().toEpochMilli())
                            .partition(p -> p.partitionId(-1).partitionOffset(-1))
                            .key(b -> b.length(willSignalKey.length())
                                .value(willSignalKey.value(), 0, willSignalKey.length()))
                            .hashKey(b -> b.length(clientId.length())
                                .value(clientId.value(), 0, clientId.length()))
                            .headersItem(h ->
                                h.nameLen(TYPE_HEADER_NAME.length())
                                    .name(TYPE_HEADER_NAME.value(), 0, TYPE_HEADER_NAME.length())
                                    .valueLen(WILL_SIGNAL_NAME.length())
                                    .value(WILL_SIGNAL_NAME.value(), 0, WILL_SIGNAL_NAME.length())))
                        .build();

                    final MqttWillSignalFW willSignal =
                        mqttWillSignalRW.wrap(willSignalBuffer, 0, willSignalBuffer.capacity())
                            .clientId(clientId)
                            .delay(delay)
                            .deliverAt(MqttWillDeliverAt.UNKNOWN.value())
                            .lifetimeId(lifetimeId)
                            .willId(willId)
                            .instanceId(instanceId.getInstanceId())
                            .build();

                    willSignalSize = willSignal.sizeof();
                    session.doKafkaData(traceId, authorization, budgetId, willSignalSize, flags,
                        willSignal, willSignalKafkaDataEx);
                    break;
                case STATE:
                    kafkaDataEx = kafkaDataExRW
                        .wrap(extBuffer, 0, extBuffer.capacity())
                        .typeId(kafkaTypeId)
                        .merged(m -> m
                            .deferred(0)
                            .timestamp(now().toEpochMilli())
                            .partition(p -> p.partitionId(-1).partitionOffset(-1))
                            .key(b -> b.length(clientId.length())
                                .value(clientId.value(), 0, clientId.length())))
                        .build();

                    kafkaPayload = mqttSessionStateRO.tryWrap(buffer, offset, limit);
                    session.doKafkaData(traceId, authorization, budgetId,
                        kafkaPayload.sizeof(), flags, kafkaPayload, kafkaDataEx);
                    break;
                }
            }
        }

        private void onMqttEnd(
            EndFW end)
        {
            final long sequence = end.sequence();
            final long acknowledge = end.acknowledge();
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            assert acknowledge <= sequence;
            assert sequence >= initialSeq;

            initialSeq = sequence;
            state = MqttKafkaState.closeInitial(state);

            assert initialAck <= initialSeq;

            if (isSetWillFlag(flags))
            {
                // Cleanup will message + will signal
                //TODO: decide if it's worth to precalculate
                String16FW key = new String16FW(clientId.asString() + WILL_KEY_POSTFIX + lifetimeId);
                Flyweight kafkaWillDataEx = kafkaDataExRW
                    .wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(kafkaTypeId)
                    .merged(m -> m
                        .deferred(0)
                        .timestamp(now().toEpochMilli())
                        .partition(p -> p.partitionId(-1).partitionOffset(-1))
                        .key(b -> b.length(key.length())
                            .value(key.value(), 0, key.length()))
                        .hashKey(b -> b.length(clientId.length())
                            .value(clientId.value(), 0, clientId.length())))
                    .build();

                session.doKafkaData(traceId, authorization, 0, 0, DATA_FLAG_COMPLETE,
                    null, kafkaWillDataEx);

                //TODO: decide if it's worth to precalculate
                String16FW willSignalKey = new String16FW(clientId.asString() + WILL_SIGNAL_KEY_POSTFIX);
                Flyweight willSignalKafkaDataEx = kafkaDataExRW
                    .wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(kafkaTypeId)
                    .merged(m -> m
                        .deferred(0)
                        .timestamp(now().toEpochMilli())
                        .partition(p -> p.partitionId(-1).partitionOffset(-1))
                        .key(b -> b.length(willSignalKey.length())
                            .value(willSignalKey.value(), 0, willSignalKey.length()))
                        .hashKey(b -> b.length(clientId.length())
                            .value(clientId.value(), 0, clientId.length()))
                        .headersItem(h ->
                            h.nameLen(TYPE_HEADER_NAME.length())
                                .name(TYPE_HEADER_NAME.value(), 0, TYPE_HEADER_NAME.length())
                                .valueLen(WILL_SIGNAL_NAME.length())
                                .value(WILL_SIGNAL_NAME.value(), 0, WILL_SIGNAL_NAME.length())))
                    .build();

                session.doKafkaData(traceId, authorization, 0, 0, DATA_FLAG_COMPLETE,
                    null, willSignalKafkaDataEx);
            }

            session.doKafkaEnd(traceId, authorization);
            if (group != null)
            {
                group.doKafkaEnd(traceId, authorization);
            }
        }

        private void onMqttAbort(
            AbortFW abort)
        {
            final long sequence = abort.sequence();
            final long acknowledge = abort.acknowledge();
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            assert acknowledge <= sequence;
            assert sequence >= initialSeq;

            initialSeq = sequence;
            state = MqttKafkaState.closeInitial(state);

            assert initialAck <= initialSeq;

            if (isSetWillFlag(flags))
            {
                session.sendWillSignal(traceId, authorization);
            }
            session.doKafkaAbort(traceId, authorization);
            if (group != null)
            {
                group.doKafkaAbort(traceId, authorization);
            }
        }

        private void onMqttReset(
            ResetFW reset)
        {
            final long sequence = reset.sequence();
            final long acknowledge = reset.acknowledge();
            final int maximum = reset.maximum();
            final long traceId = reset.traceId();

            assert acknowledge <= sequence;
            assert sequence <= replySeq;
            assert acknowledge >= replyAck;
            assert maximum >= replyMax;

            replyAck = acknowledge;
            replyMax = maximum;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

            session.doKafkaReset(traceId);
            if (group != null)
            {
                group.doKafkaReset(traceId);
            }
        }

        private void onMqttWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();
            final long traceId = window.traceId();
            final long authorization = window.authorization();
            final long budgetId = window.budgetId();
            final int padding = window.padding();
            final int capabilities = window.capabilities();

            assert acknowledge <= sequence;
            assert sequence <= replySeq;
            assert acknowledge >= replyAck;
            assert maximum >= replyMax;

            replyAck = acknowledge;
            replyMax = maximum;
            replyPad = padding;
            state = MqttKafkaState.openReply(state);

            assert replyAck <= replySeq;

            session.doKafkaWindow(traceId, authorization, budgetId, capabilities);
            if (sequence == 0 && group != null)
            {
                group.doKafkaWindow(traceId, authorization, budgetId, padding, capabilities);
            }
        }

        private void doMqttBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            if (!MqttKafkaState.replyOpening(state))
            {
                replySeq = session.replySeq;
                replyAck = session.replyAck;
                replyMax = session.replyMax;
                state = MqttKafkaState.openingReply(state);

                doBegin(mqtt, originId, routedId, replyId, replySeq, replyAck, replyMax,
                    traceId, authorization, affinity);
            }
        }

        private void doMqttData(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            int flags,
            MqttSessionStateFW sessionState)
        {
            Flyweight state = sessionState == null ? EMPTY_OCTETS : sessionState;
            final DirectBuffer buffer = state.buffer();
            final int offset = state.offset();
            final int limit = state.limit();
            final int length = limit - offset;

            doData(mqtt, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, flags, reserved, buffer, offset, length, EMPTY_OCTETS);

            replySeq += reserved;

            assert replySeq <= replyAck + replyMax;
        }

        private void doMqttData(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            int flags,
            OctetsFW payload)
        {
            doData(mqtt, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, flags, reserved, payload, EMPTY_OCTETS);

            replySeq += reserved;

            assert replySeq <= replyAck + replyMax;
        }

        private void doMqttAbort(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.replyClosed(state))
            {
                replySeq = session.replySeq;
                state = MqttKafkaState.closeReply(state);

                doAbort(mqtt, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization);
            }
        }

        private void doMqttEnd(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.replyClosed(state))
            {
                replySeq = session.replySeq;
                state = MqttKafkaState.closeReply(state);

                doEnd(mqtt, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization);
            }
        }

        private void doMqttWindow(
            long authorization,
            long traceId,
            long budgetId,
            int padding,
            int capabilities)
        {
            initialAck = session.initialAck - willPadding - willSignalSize;
            initialMax = session.initialMax;

            doWindow(mqtt, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, budgetId, padding, 0, capabilities);
        }

        private void doMqttReset(
            long traceId)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                state = MqttKafkaState.closeInitial(state);

                doReset(mqtt, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId);
            }
        }
    }

    private final class KafkaWillProxy
    {
        private MessageConsumer kafka;
        private final long originId;
        private final long routedId;
        private final long initialId;
        private final long replyId;
        private final String16FW sessionsTopic;
        private final String16FW messagesTopic;
        private final String16FW retainedTopic;
        private final Object2ObjectHashMap<String, KafkaFetchWillProxy> willFetchers;
        private int state;

        private long initialSeq;
        private long initialAck;
        private int initialMax;

        private long replySeq;
        private long replyAck;
        private int replyMax;
        private int replyPad;

        private KafkaWillProxy(
            long originId,
            long routedId,
            String16FW sessionsTopic,
            String16FW messagesTopic,
            String16FW retainedTopic)
        {
            this.originId = originId;
            this.routedId = routedId;
            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.sessionsTopic = sessionsTopic;
            this.messagesTopic = messagesTopic;
            this.retainedTopic = retainedTopic;
            this.replyId = supplyReplyId.applyAsLong(initialId);
        }

        private void doKafkaBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            state = MqttKafkaState.openingInitial(state);

            kafka = newWillStream(this::onWillMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, sessionsTopic);
        }

        private void doKafkaEnd(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                state = MqttKafkaState.closeInitial(state);

                doEnd(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void onWillMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onKafkaBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onKafkaData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onKafkaEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onKafkaAbort(abort);
                break;
            }
        }

        private void onKafkaBegin(
            BeginFW begin)
        {
            final long sequence = begin.sequence();
            final long acknowledge = begin.acknowledge();
            final int maximum = begin.maximum();
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;
            assert acknowledge >= replyAck;

            replySeq = sequence;
            replyAck = acknowledge;
            replyMax = maximum;
            state = MqttKafkaState.openingReply(state);

            assert replyAck <= replySeq;
            doKafkaWindow(traceId, authorization, 0, 0, 0);
        }

        private void onKafkaData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + reserved;

            assert replyAck <= replySeq;
            if (replySeq > replyAck + replyMax)
            {
                doKafkaReset(traceId);
            }
            else
            {
                final OctetsFW extension = data.extension();
                final OctetsFW payload = data.payload();
                final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
                final KafkaDataExFW kafkaDataEx =
                    dataEx != null && dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
                final KafkaMergedDataExFW kafkaMergedDataEx =
                    kafkaDataEx != null && kafkaDataEx.kind() == KafkaDataExFW.KIND_MERGED ? kafkaDataEx.merged() : null;
                final KafkaKeyFW key = kafkaMergedDataEx != null ? kafkaMergedDataEx.key() : null;

                fetchWill:
                if (key != null)
                {
                    if (payload == null)
                    {
                        //TODO: more elegant way?
                        final String clientId0 = key.value().get((b, o, m) -> b.getStringWithoutLengthUtf8(o, m - o))
                            .split(WILL_SIGNAL_KEY_POSTFIX)[0];
                        String16FW clientId = new String16FW(clientId0);
                        System.out.println(willDeliverIds);
                        if (willDeliverIds.containsKey(clientId))
                        {
                            final long willDeliverSignalId = willDeliverIds.get(clientId);
                            signaler.cancel(willDeliverSignalId);
                        }
                        break fetchWill;
                    }
                    MqttWillSignalFW willSignal =
                        mqttWillSignalRO.tryWrap(payload.buffer(), payload.offset(), payload.limit());

                    long deliverAt = willSignal.deliverAt();
                    final String16FW clientId = willSignal.clientId();


                    if (deliverAt == MqttWillDeliverAt.UNKNOWN.value())
                    {
                        if (!instanceId.getInstanceId().equals(willSignal.instanceId()))
                        {
                            deliverAt = supplyTime.get() + SECONDS.toMillis(willSignal.delay());
                        }
                        else
                        {
                            break fetchWill;
                        }
                    }

                    KafkaFetchWillProxy willFetcher = new KafkaFetchWillProxy(originId, routedId, this, sessionsTopic,
                        clientId, willSignal.willId().asString(), willSignal.lifetimeId().asString(), deliverAt);
                    willFetcher.doKafkaBegin(traceId, authorization, 0, willSignal.lifetimeId());
                }
            }
        }

        private void onKafkaEnd(
            EndFW end)
        {
            final long sequence = end.sequence();
            final long acknowledge = end.acknowledge();
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;
        }

        private void onKafkaAbort(
            AbortFW abort)
        {
            final long sequence = abort.sequence();
            final long acknowledge = abort.acknowledge();
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;
        }

        private void doKafkaReset(
            long traceId)
        {
            if (!MqttKafkaState.replyClosed(state))
            {
                state = MqttKafkaState.closeReply(state);

                doReset(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId);
            }
        }

        private void doKafkaWindow(
            long traceId,
            long authorization,
            long budgetId,
            int padding,
            int capabilities)
        {
            //TODO: Add a config for this
            replyMax = 8192;

            doWindow(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, padding, replyPad, capabilities);
        }


        private void doKafkaData(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            int flags,
            OctetsFW payload,
            Flyweight extension)
        {

            doData(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, budgetId, flags, reserved, payload, extension);

            initialSeq += reserved;

            assert initialSeq <= initialAck + initialMax;
        }
    }

    private final class KafkaFetchWillProxy
    {
        private final KafkaWillProxy delegate;
        private final String16FW topic;
        private final String16FW clientId;
        private final String lifetimeId;
        private final String willId;
        private final long deliverAt;
        private MessageConsumer kafka;
        private final long originId;
        private final long routedId;
        private final long initialId;
        private final long replyId;

        private int state;

        private long initialSeq;
        private long initialAck;
        private int initialMax;

        private long replySeq;
        private long replyAck;
        private int replyMax;
        private int replyPad;

        private int dataSlot = NO_SLOT;
        private int messageSlotOffset;
        private int messageSlotReserved;
        private KafkaProduceWillProxy willProducer;
        private KafkaProduceWillProxy willRetainProducer;

        private KafkaFetchWillProxy(
            long originId,
            long routedId,
            KafkaWillProxy delegate,
            String16FW topic,
            String16FW clientId,
            String willId,
            String lifetimeId,
            long deliverAt)
        {
            this.originId = originId;
            this.routedId = routedId;
            this.delegate = delegate;
            this.topic = topic;
            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.clientId = clientId;
            this.willId = willId;
            this.lifetimeId = lifetimeId;
            this.deliverAt = deliverAt;
        }

        private void doKafkaBegin(
            long traceId,
            long authorization,
            long affinity,
            String16FW lifetimeId)
        {
            if (!MqttKafkaState.initialOpening(state))
            {
                state = MqttKafkaState.openingInitial(state);

                kafka = newKafkaStream(this::onKafkaMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, affinity, clientId, lifetimeId, topic);
            }
        }

        private void doKafkaEnd(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                state = MqttKafkaState.closeInitial(state);

                doEnd(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void doKafkaAbort(
            long traceId,
            long authorization)
        {
            if (MqttKafkaState.initialOpened(state) && !MqttKafkaState.initialClosed(state))
            {
                state = MqttKafkaState.closeInitial(state);

                doAbort(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void onKafkaMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onKafkaBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onKafkaData(data);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onKafkaAbort(abort);
                break;
            case FlushFW.TYPE_ID:
                final FlushFW flush = flushRO.wrap(buffer, index, index + length);
                onKafkaFlush(flush);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onKafkaWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onKafkaReset(reset);
                break;
            }
        }

        private void onKafkaBegin(
            BeginFW begin)
        {
            final long sequence = begin.sequence();
            final long acknowledge = begin.acknowledge();
            final int maximum = begin.maximum();
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;
            assert acknowledge >= replyAck;

            replySeq = sequence;
            replyAck = acknowledge;
            replyMax = maximum;
            state = MqttKafkaState.openingReply(state);

            assert replyAck <= replySeq;

            doKafkaWindow(traceId, authorization, 0, 0, 0);
        }

        private void onKafkaData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + reserved;


            assert replyAck <= replySeq;

            if (replySeq > replyAck + replyMax)
            {
                doKafkaReset(traceId);
            }
            else
            {
                final OctetsFW extension = data.extension();
                final OctetsFW payload = data.payload();
                final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
                final KafkaDataExFW kafkaDataEx =
                    dataEx != null && dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
                final KafkaMergedDataExFW kafkaMergedDataEx =
                    kafkaDataEx != null && kafkaDataEx.kind() == KafkaDataExFW.KIND_MERGED ? kafkaDataEx.merged() : null;
                final KafkaKeyFW key = kafkaMergedDataEx != null ? kafkaMergedDataEx.key() : null;

                if (key != null)
                {
                    MqttMessageFW willMessage =
                        mqttWillRO.tryWrap(payload.buffer(), payload.offset(), payload.limit());

                    if (willId.equals(willMessage.willId().asString()))
                    {
                        if (dataSlot == NO_SLOT)
                        {
                            dataSlot = bufferPool.acquire(initialId);
                        }

                        if (dataSlot == NO_SLOT)
                        {
                            doKafkaAbort(traceId, authorization);
                        }


                        final MutableDirectBuffer dataBuffer = bufferPool.buffer(dataSlot);
                        dataBuffer.putBytes(0, willMessage.buffer(), willMessage.offset(), willMessage.sizeof());

                        messageSlotReserved = willMessage.sizeof();

                        willProducer =
                            new KafkaProduceWillProxy(originId, routedId, this, delegate.messagesTopic, deliverAt);
                        willProducer.doKafkaBegin(traceId, authorization, 0);
                        if ((willMessage.flags() & 1 << MqttPublishFlags.RETAIN.value()) != 0)
                        {
                            willRetainProducer =
                                new KafkaProduceWillProxy(originId, routedId, this, delegate.retainedTopic, deliverAt);
                            willRetainProducer.doKafkaBegin(traceId, authorization, 0);
                        }
                    }
                    else
                    {
                        doKafkaEnd(traceId, authorization);
                    }
                }
            }
        }

        private void onKafkaFlush(
            FlushFW flush)
        {
            final long sequence = flush.sequence();
            final long acknowledge = flush.acknowledge();
            final long traceId = flush.traceId();
            final long authorization = flush.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;

            assert replyAck <= replySeq;

            doKafkaEnd(traceId, authorization);
        }

        private void onKafkaAbort(
            AbortFW abort)
        {
            final long sequence = abort.sequence();
            final long acknowledge = abort.acknowledge();
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

        }

        private void onKafkaWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();

            assert acknowledge <= sequence;

            initialAck = acknowledge;
            initialMax = maximum;
            state = MqttKafkaState.openInitial(state);

            assert initialAck <= initialSeq;
        }

        private void onKafkaReset(
            ResetFW reset)
        {
            final long sequence = reset.sequence();
            final long acknowledge = reset.acknowledge();
            final long traceId = reset.traceId();

            assert acknowledge <= sequence;
        }

        private void doKafkaReset(
            long traceId)
        {
            if (MqttKafkaState.initialOpened(state) && !MqttKafkaState.replyClosed(state))
            {
                state = MqttKafkaState.closeReply(state);

                doReset(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId);
            }
        }

        private void doKafkaWindow(
            long traceId,
            long authorization,
            long budgetId,
            int padding,
            int capabilities)
        {
            replyMax = bufferPool.slotCapacity();

            doWindow(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, padding, replyPad, capabilities);
        }

        public void releaseBufferSlot(
            long traceId,
            long authorization)
        {
            final boolean shouldRelease = willRetainProducer == null && willProducer.initialAck == messageSlotReserved ||
                Math.min(willProducer.initialAck, willRetainProducer.initialAck) == messageSlotReserved;
            if (shouldRelease)
            {
                bufferPool.release(dataSlot);
                dataSlot = NO_SLOT;
                messageSlotOffset = 0;

                // Cleanup will message + will signal
                String16FW key = new String16FW(clientId.asString() + WILL_KEY_POSTFIX + lifetimeId);
                Flyweight kafkaWillDataEx = kafkaDataExRW
                    .wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(kafkaTypeId)
                    .merged(m -> m
                        .deferred(0)
                        .timestamp(now().toEpochMilli())
                        .partition(p -> p.partitionId(-1).partitionOffset(-1))
                        .key(b -> b.length(key.length())
                            .value(key.value(), 0, key.length()))
                        .hashKey(b -> b.length(clientId.length())
                            .value(clientId.value(), 0, clientId.length())))
                    .build();

                delegate.doKafkaData(traceId, authorization, 0, 0, DATA_FLAG_COMPLETE, null, kafkaWillDataEx);

                String16FW willSignalKey = new String16FW(clientId.asString() + WILL_SIGNAL_KEY_POSTFIX);
                Flyweight willSignalKafkaDataEx = kafkaDataExRW
                    .wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(kafkaTypeId)
                    .merged(m -> m
                        .deferred(0)
                        .timestamp(now().toEpochMilli())
                        .partition(p -> p.partitionId(-1).partitionOffset(-1))
                        .key(b -> b.length(willSignalKey.length())
                            .value(willSignalKey.value(), 0, willSignalKey.length()))
                        .hashKey(b -> b.length(clientId.length())
                            .value(clientId.value(), 0, clientId.length()))
                        .headersItem(h ->
                            h.nameLen(TYPE_HEADER_NAME.length())
                                .name(TYPE_HEADER_NAME.value(), 0, TYPE_HEADER_NAME.length())
                                .valueLen(WILL_SIGNAL_NAME.length())
                                .value(WILL_SIGNAL_NAME.value(), 0, WILL_SIGNAL_NAME.length())))
                    .build();

                delegate.doKafkaData(traceId, authorization, 0, 0, DATA_FLAG_COMPLETE,
                    null, willSignalKafkaDataEx);

                doKafkaEnd(traceId, authorization);
            }
        }
    }

    private final class KafkaProduceWillProxy
    {
        private MessageConsumer kafka;
        private final long originId;
        private final long routedId;
        private final long initialId;
        private final String16FW kafkaTopic;
        private final long deliverAt;
        private final long replyId;
        private final KafkaFetchWillProxy delegate;

        private int state;

        private long initialSeq;
        private long initialAck;
        private int initialMax;

        private long replySeq;
        private long replyAck;
        private int replyMax;
        private int replyPad;

        private KafkaProduceWillProxy(
            long originId,
            long routedId,
            KafkaFetchWillProxy delegate,
            String16FW kafkaTopic,
            long deliverAt)
        {
            this.originId = originId;
            this.routedId = routedId;
            this.delegate = delegate;
            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.kafkaTopic = kafkaTopic;
            this.deliverAt = deliverAt;
            this.replyId = supplyReplyId.applyAsLong(initialId);
        }

        private void doKafkaBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            initialSeq = delegate.initialSeq;
            initialAck = delegate.initialAck;
            initialMax = delegate.initialMax;
            state = MqttKafkaState.openingInitial(state);

            kafka = newKafkaStream(this::onKafkaMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, kafkaTopic);
        }

        private void doKafkaData(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            int flags,
            OctetsFW payload,
            Flyweight extension)
        {
            doData(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, budgetId, flags, reserved, payload, extension);

            initialSeq += reserved;

            assert initialSeq <= initialAck + initialMax;
        }

        private void doKafkaEnd(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                state = MqttKafkaState.closeInitial(state);

                doEnd(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void doKafkaWindow(
            long traceId,
            long authorization,
            long budgetId,
            int padding,
            int capabilities)
        {
            doWindow(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, padding, replyPad, capabilities);
        }

        private void onKafkaMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onKafkaBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onKafkaData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onKafkaEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onKafkaAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onKafkaWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onKafkaReset(reset);
                break;
            case SignalFW.TYPE_ID:
                final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                onSignal(signal);
                break;
            }
        }

        private void onSignal(SignalFW signal)
        {
            final int signalId = signal.signalId();

            switch (signalId)
            {
            case SIGNAL_WILL_DELIVER:
                onWillDeliverSignal(signal);
                break;
            default:
                break;
            }
        }

        private void onKafkaBegin(
            BeginFW begin)
        {
            final long sequence = begin.sequence();
            final long acknowledge = begin.acknowledge();
            final int maximum = begin.maximum();
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;
            assert acknowledge >= replyAck;

            replySeq = sequence;
            replyAck = acknowledge;
            replyMax = maximum;
            state = MqttKafkaState.openingReply(state);

            assert replyAck <= replySeq;

            doKafkaWindow(traceId, authorization, 0, 0, 0);
        }

        private void onKafkaData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + reserved;

            assert replyAck <= replySeq;
            doKafkaReset(traceId);
        }

        private void onKafkaEnd(
            EndFW end)
        {
            final long sequence = end.sequence();
            final long acknowledge = end.acknowledge();
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;
        }

        private void onKafkaAbort(
            AbortFW abort)
        {
            final long sequence = abort.sequence();
            final long acknowledge = abort.acknowledge();
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

        }

        private void onKafkaWindow(
            WindowFW window)
        {
            final long traceId = window.traceId();
            final long authorization = window.authorization();
            final long budgetId = window.budgetId();
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();
            final boolean wasOpen = MqttKafkaState.initialOpened(state);

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;
            assert maximum >= delegate.initialMax;

            initialAck = acknowledge;
            initialMax = maximum;
            state = MqttKafkaState.openInitial(state);

            assert initialAck <= initialSeq;

            if (!wasOpen)
            {
                final long signalId =
                    signaler.signalAt(deliverAt, originId, routedId, initialId, SIGNAL_WILL_DELIVER, 0);
                willDeliverIds.put(delegate.clientId, signalId);
            }
            else
            {
                doKafkaEnd(traceId, authorization);
                delegate.releaseBufferSlot(traceId, authorization);
            }
        }


        private void onWillDeliverSignal(SignalFW signal)
        {
            sendWill(signal.traceId(), signal.authorization(), 0);
            willDeliverIds.remove(delegate.clientId);
        }

        private void sendWill(
            long traceId,
            long authorization,
            long budgetId)
        {
            final MutableDirectBuffer dataBuffer = bufferPool.buffer(delegate.dataSlot);
            // TODO: data fragmentation
            final MqttMessageFW will = mqttWillRO.wrap(dataBuffer, delegate.messageSlotOffset, dataBuffer.capacity());

            Flyweight kafkaDataEx;

            kafkaHeadersRW.wrap(kafkaHeadersBuffer, 0, kafkaHeadersBuffer.capacity());


            String topicName = will.topic().asString();
            assert topicName != null;

            final DirectBuffer topicNameBuffer = will.topic().value();

            final MutableDirectBuffer keyBuffer = new UnsafeBuffer(new byte[topicNameBuffer.capacity() + 4]);
            final KafkaKeyFW key = new KafkaKeyFW.Builder()
                .wrap(keyBuffer, 0, keyBuffer.capacity())
                .length(topicNameBuffer.capacity())
                .value(topicNameBuffer, 0, topicNameBuffer.capacity())
                .build();

            String[] topicHeaders = topicName.split("/");
            for (String header : topicHeaders)
            {
                String16FW topicHeader = new String16FW(header);
                addHeader(helper.kafkaFilterHeaderName, topicHeader);
            }

            if (will.expiryInterval() != -1)
            {
                final MutableDirectBuffer expiryBuffer = new UnsafeBuffer(new byte[4]);
                expiryBuffer.putInt(0, (int) TimeUnit.SECONDS.toMillis(will.expiryInterval()), ByteOrder.BIG_ENDIAN);
                kafkaHeadersRW.item(h ->
                {
                    h.nameLen(helper.kafkaTimeoutHeaderName.sizeof());
                    h.name(helper.kafkaTimeoutHeaderName);
                    h.valueLen(4);
                    h.value(expiryBuffer, 0, expiryBuffer.capacity());
                });
            }

            if (will.contentType().asString() != null)
            {
                addHeader(helper.kafkaContentTypeHeaderName, will.contentType());
            }

            if (will.payload().sizeof() != 0 && will.format() != null)
            {
                addHeader(helper.kafkaFormatHeaderName, will.format());
            }

            if (will.responseTopic().asString() != null)
            {
                final String16FW responseTopic = will.responseTopic();
                addHeader(helper.kafkaReplyToHeaderName, kafkaTopic);
                addHeader(helper.kafkaReplyKeyHeaderName, responseTopic);

                addFiltersHeader(responseTopic);
            }

            if (will.correlation().bytes() != null)
            {
                addHeader(helper.kafkaCorrelationHeaderName, will.correlation().bytes());
            }

            will.properties().forEach(property ->
                addHeader(property.key(), property.value()));

            kafkaDataEx = kafkaDataExRW
                .wrap(extBuffer, 0, extBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m -> m
                    .deferred(0)
                    .timestamp(now().toEpochMilli())
                    .partition(p -> p.partitionId(-1).partitionOffset(-1))
                    .key(b -> b.set(key))
                    .headers(kafkaHeadersRW.build()))
                .build();

            doKafkaData(traceId, authorization, budgetId, will.sizeof(), DATA_FLAG_COMPLETE, will.payload().bytes(), kafkaDataEx);
        }

        private void onKafkaReset(
            ResetFW reset)
        {
            final long sequence = reset.sequence();
            final long acknowledge = reset.acknowledge();
            final long traceId = reset.traceId();

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;

            delegate.initialAck = acknowledge;

            assert delegate.initialAck <= delegate.initialSeq;
        }

        private void doKafkaReset(
            long traceId)
        {
            if (!MqttKafkaState.replyClosed(state))
            {
                state = MqttKafkaState.closeReply(state);

                doReset(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId);
            }
        }

        private void addHeader(
            OctetsFW key,
            OctetsFW value)
        {
            kafkaHeadersRW.item(h ->
            {
                h.nameLen(key.sizeof());
                h.name(key);
                h.valueLen(value.sizeof());
                h.value(value);
            });
        }

        private void addFiltersHeader(
            String16FW responseTopic)
        {
            final DirectBuffer responseBuffer = responseTopic.value();
            final int capacity = responseBuffer.capacity();

            int offset = 0;
            int matchAt = 0;
            while (offset >= 0 && offset < capacity && matchAt != -1)
            {
                matchAt = indexOfByte(responseBuffer, offset, capacity, SLASH_BYTE);
                if (matchAt != -1)
                {
                    addHeader(helper.kafkaReplyFilterHeaderName, responseBuffer, offset, matchAt - offset);
                    offset = matchAt + 1;
                }
            }
            addHeader(helper.kafkaReplyFilterHeaderName, responseBuffer, offset, capacity - offset);
        }

        private void addHeader(
            OctetsFW key,
            MqttPayloadFormatFW format)
        {
            String16FW value = format.get() == MqttPayloadFormat.BINARY ? binaryFormat : textFormat;
            addHeader(key, value);
        }

        private void addHeader(
            OctetsFW key,
            String16FW value)
        {
            DirectBuffer buffer = value.value();
            kafkaHeadersRW.item(h ->
            {
                h.nameLen(key.sizeof());
                h.name(key);
                h.valueLen(value.length());
                h.value(buffer, 0, buffer.capacity());
            });
        }

        private void addHeader(
            OctetsFW key,
            DirectBuffer buffer,
            int offset,
            int length)
        {
            kafkaHeadersRW.item(h ->
            {
                h.nameLen(key.sizeof());
                h.name(key);
                h.valueLen(length);
                h.value(buffer, offset, length);
            });
        }

        private void addHeader(String16FW key, String16FW value)
        {
            DirectBuffer keyBuffer = key.value();
            DirectBuffer valueBuffer = value.value();
            kafkaHeadersRW.item(h ->
            {
                h.nameLen(key.length());
                h.name(keyBuffer, 0, keyBuffer.capacity());
                h.valueLen(value.length());
                h.value(valueBuffer, 0, valueBuffer.capacity());
            });
        }
    }

    private static int indexOfByte(
        DirectBuffer buffer,
        int offset,
        int limit,
        byte value)
    {
        int byteAt = -1;
        for (int index = offset; index < limit; index++)
        {
            if (buffer.getByte(index) == value)
            {
                byteAt = index;
                break;
            }
        }
        return byteAt;
    }

    private static boolean isSetWillFlag(
        int flags)
    {
        return (flags & MqttSessionFlags.WILL.value() << 1) != 0;
    }

    private static boolean isCleanStart(
        int flags)
    {
        return (flags & MqttSessionFlags.CLEAN_START.value() << 1) != 0;
    }

    private abstract class KafkaSessionProxy
    {
        protected MessageConsumer kafka;
        protected final long originId;
        protected final long routedId;
        protected long initialId;
        protected long replyId;
        protected final MqttSessionProxy delegate;

        protected int state;

        protected long initialSeq;
        protected long initialAck;
        protected int initialMax;

        protected long replySeq;
        protected long replyAck;
        protected int replyMax;
        protected int replyPad;

        private KafkaSessionProxy(
            long originId,
            long routedId,
            MqttSessionProxy delegate)
        {
            this.originId = originId;
            this.routedId = routedId;
            this.delegate = delegate;
            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);
        }

        private void doKafkaBeginIfNecessary(
            long traceId,
            long authorization,
            long affinity)
        {
            if (!MqttKafkaState.initialOpening(state))
            {
                doKafkaBegin(traceId, authorization, affinity);
            }
        }

        protected void doKafkaData(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            int flags,
            OctetsFW payload,
            Flyweight extension)
        {

            doData(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, budgetId, flags, reserved, payload, extension);

            initialSeq += reserved;

            assert initialSeq <= initialAck + initialMax;
        }

        private void sendWillSignal(
            long traceId,
            long authorization)
        {
            String16FW willSignalKey = new String16FW(delegate.clientId.asString() + WILL_SIGNAL_KEY_POSTFIX);
            Flyweight willSignalKafkaDataEx = kafkaDataExRW
                .wrap(extBuffer, 0, extBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m -> m
                    .deferred(0)
                    .timestamp(now().toEpochMilli())
                    .partition(p -> p.partitionId(-1).partitionOffset(-1))
                    .key(b -> b.length(willSignalKey.length())
                        .value(willSignalKey.value(), 0, willSignalKey.length()))
                    .hashKey(b -> b.length(delegate.clientId.length())
                        .value(delegate.clientId.value(), 0, delegate.clientId.length()))
                    .headersItem(h ->
                        h.nameLen(TYPE_HEADER_NAME.length())
                            .name(TYPE_HEADER_NAME.value(), 0, TYPE_HEADER_NAME.length())
                            .valueLen(WILL_SIGNAL_NAME.length())
                            .value(WILL_SIGNAL_NAME.value(), 0, WILL_SIGNAL_NAME.length())))
                .build();

            final MqttWillSignalFW willSignal =
                mqttWillSignalRW.wrap(willSignalBuffer, 0, willSignalBuffer.capacity())
                    .clientId(delegate.clientId)
                    .delay(delegate.delay)
                    .deliverAt(supplyTime.get() + delegate.delay)
                    .lifetimeId(delegate.lifetimeId)
                    .willId(delegate.willId)
                    .instanceId(instanceId.getInstanceId())
                    .build();

            delegate.willSignalSize += willSignal.sizeof();
            doKafkaData(traceId, authorization, 0, willSignal.sizeof(), DATA_FLAG_COMPLETE,
                willSignal, willSignalKafkaDataEx);
        }

        private void doKafkaData(
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            int flags,
            Flyweight payload,
            Flyweight extension)
        {
            final DirectBuffer buffer = payload.buffer();
            final int offset = payload.offset();
            final int limit = payload.limit();
            final int length = limit - offset;

            doData(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, budgetId, flags, reserved, buffer, offset, length, extension);

            initialSeq += reserved;

            assert initialSeq <= initialAck + initialMax;
        }

        private void doKafkaEnd(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                initialSeq = delegate.initialSeq;
                initialAck = delegate.initialAck;
                initialMax = delegate.initialMax;
                state = MqttKafkaState.closeInitial(state);

                doEnd(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void doKafkaAbort(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                initialSeq = delegate.initialSeq;
                initialAck = delegate.initialAck;
                initialMax = delegate.initialMax;
                state = MqttKafkaState.closeInitial(state);

                doAbort(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void onKafkaMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onKafkaBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onKafkaData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onKafkaEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onKafkaAbort(abort);
                break;
            case FlushFW.TYPE_ID:
                final FlushFW flush = flushRO.wrap(buffer, index, index + length);
                onKafkaFlush(flush);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onKafkaWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onKafkaReset(reset);
                break;
            }
        }

        private void onKafkaBegin(
            BeginFW begin)
        {
            final long sequence = begin.sequence();
            final long acknowledge = begin.acknowledge();
            final int maximum = begin.maximum();
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;
            assert acknowledge >= replyAck;

            replySeq = sequence;
            replyAck = acknowledge;
            replyMax = maximum;
            state = MqttKafkaState.openingReply(state);

            assert replyAck <= replySeq;

            delegate.doMqttBegin(traceId, authorization, affinity);
            doKafkaWindow(traceId, authorization, 0, 0);
        }

        private void onKafkaData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final int reserved = data.reserved();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + reserved;

            assert replyAck <= replySeq;

            if (replySeq > replyAck + replyMax)
            {
                doKafkaReset(traceId);
                delegate.doMqttAbort(traceId, authorization);
            }
            else
            {
                handleKafkaData(data);
            }
        }

        protected abstract void doKafkaBegin(long traceId, long authorization, long affinity);

        protected abstract void handleKafkaData(DataFW data);

        protected void onKafkaWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;
            assert maximum >= delegate.initialMax;

            initialAck = acknowledge;
            initialMax = maximum;
            state = MqttKafkaState.openInitial(state);

            assert initialAck <= initialSeq;
        }

        protected void onKafkaEnd(
            EndFW end)
        {
        }

        protected void onKafkaFlush(
            FlushFW flush)
        {
        }

        private void onKafkaAbort(
            AbortFW abort)
        {
            final long sequence = abort.sequence();
            final long acknowledge = abort.acknowledge();
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

            delegate.doMqttAbort(traceId, authorization);
        }

        protected void sendMigrateSignal(long authorization, long traceId)
        {
            Flyweight kafkaMigrateDataEx = kafkaDataExRW
                .wrap(extBuffer, 0, extBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m -> m
                    .deferred(0)
                    .timestamp(now().toEpochMilli())
                    .partition(p -> p.partitionId(-1).partitionOffset(-1))
                    .key(b -> b.length(delegate.clientIdMigrate.length())
                        .value(delegate.clientIdMigrate.value(), 0, delegate.clientIdMigrate.length()))
                    .hashKey(b -> b.length(delegate.clientId.length())
                        .value(delegate.clientId.value(), 0, delegate.clientId.length()))
                    .headersItem(c -> c.nameLen(SENDER_ID_NAME.length())
                        .name(SENDER_ID_NAME.value(), 0, SENDER_ID_NAME.length())
                        .valueLen(delegate.sessionId.length())
                        .value(delegate.sessionId.value(), 0, delegate.sessionId.length())))
                .build();

            doKafkaData(traceId, authorization, 0, 0, DATA_FLAG_COMPLETE,
                EMPTY_OCTETS, kafkaMigrateDataEx);
        }

        private void onKafkaReset(
            ResetFW reset)
        {
            final long sequence = reset.sequence();
            final long acknowledge = reset.acknowledge();
            final long traceId = reset.traceId();

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;

            delegate.initialAck = acknowledge;

            assert delegate.initialAck <= delegate.initialSeq;

            delegate.doMqttReset(traceId);
        }

        private void doKafkaReset(
            long traceId)
        {
            if (!MqttKafkaState.replyClosed(state))
            {
                state = MqttKafkaState.closeReply(state);

                doReset(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId);
            }
        }

        private void doKafkaWindow(
            long traceId,
            long authorization,
            long budgetId,
            int capabilities)
        {
            replyAck = delegate.replyAck;
            replyMax = delegate.replyMax;
            replyPad = delegate.replyPad;

            doWindow(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, replyPad, 0, capabilities);
        }
    }

    private final class KafkaSessionSignalProxy extends KafkaSessionProxy
    {
        private KafkaSessionSignalProxy(
            long originId,
            long routedId,
            MqttSessionProxy delegate)
        {
            super(originId, routedId, delegate);
        }

        @Override
        protected void doKafkaBegin(long traceId, long authorization, long affinity)
        {
            assert state == 0;

            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);

            state = MqttKafkaState.openingInitial(state);

            kafka = newKafkaStream(super::onKafkaMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, delegate.sessionsTopic, null,
                delegate.clientIdMigrate, delegate.sessionId);
        }

        @Override
        protected void handleKafkaData(DataFW data)
        {
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();

            final OctetsFW extension = data.extension();
            final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
            final KafkaDataExFW kafkaDataEx =
                dataEx != null && dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
            final KafkaMergedDataExFW kafkaMergedDataEx =
                kafkaDataEx != null && kafkaDataEx.kind() == KafkaDataExFW.KIND_MERGED ? kafkaDataEx.merged() : null;
            final KafkaKeyFW key = kafkaMergedDataEx != null ? kafkaMergedDataEx.key() : null;

            if (key != null)
            {
                //TODO: from now on, it can receive back will messages, as we publish it when opening this
                delegate.group.doKafkaFlush(traceId, authorization, budgetId, reserved);
            }
        }

        @Override
        protected void onKafkaWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();
            final long authorization = window.authorization();
            final long traceId = window.traceId();
            final boolean wasOpen = MqttKafkaState.initialOpened(state);

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;
            assert maximum >= delegate.initialMax;

            initialAck = acknowledge;
            initialMax = maximum;
            state = MqttKafkaState.openInitial(state);

            assert initialAck <= initialSeq;

            if (!wasOpen)
            {
                sendMigrateSignal(authorization, traceId);

                final long routedId = delegate.session.routedId;

                delegate.group = new KafkaGroupProxy(originId, routedId, delegate);
                delegate.group.doKafkaBegin(traceId, authorization, 0);
            }
        }
    }

    private final class KafkaSessionStateProxy extends KafkaSessionProxy
    {
        private boolean sessionReady;

        private KafkaSessionStateProxy(
            long originId,
            long routedId,
            MqttSessionProxy delegate)
        {
            super(originId, routedId, delegate);
        }

        @Override
        protected void doKafkaBegin(long traceId, long authorization, long affinity)
        {
            assert state == 0;

            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);

            state = MqttKafkaState.openingInitial(state);

            kafka = newKafkaStream(super::onKafkaMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, delegate.sessionsTopic, delegate.clientId,
                delegate.clientIdMigrate, delegate.sessionId);
        }

        @Override
        protected void handleKafkaData(DataFW data)
        {
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();

            final int flags = data.flags();
            final OctetsFW payload = data.payload();
            final OctetsFW extension = data.extension();
            final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
            final KafkaDataExFW kafkaDataEx =
                dataEx != null && dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
            final KafkaMergedDataExFW kafkaMergedDataEx =
                kafkaDataEx != null && kafkaDataEx.kind() == KafkaDataExFW.KIND_MERGED ? kafkaDataEx.merged() : null;
            final KafkaKeyFW key = kafkaMergedDataEx != null ? kafkaMergedDataEx.key() : null;

            if (key != null)
            {
                if (key.length() == (delegate.clientId.length()))
                {
                    MqttSessionStateFW sessionState =
                        mqttSessionStateRO.tryWrap(payload.buffer(), payload.offset(), payload.limit());
                    delegate.doMqttData(traceId, authorization, budgetId, reserved, flags, sessionState);
                }
                else if (key.length() == delegate.clientIdMigrate.length())
                {
                    delegate.group.doKafkaFlush(traceId, authorization, budgetId, reserved);
                }
            }
        }

        @Override
        protected void onKafkaWindow(
            WindowFW window)
        {
            final long sequence = window.sequence();
            final long acknowledge = window.acknowledge();
            final int maximum = window.maximum();
            final long authorization = window.authorization();
            final long traceId = window.traceId();
            final long budgetId = window.budgetId();
            final int padding = window.padding();
            final int capabilities = window.capabilities();
            final boolean wasOpen = MqttKafkaState.initialOpened(state);

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;
            assert maximum >= delegate.initialMax;

            initialAck = acknowledge;
            initialMax = maximum;
            state = MqttKafkaState.openInitial(state);

            assert initialAck <= initialSeq;

            if (!wasOpen && !isCleanStart(delegate.flags))
            {
                String16FW willSignalKey = new String16FW(delegate.clientId.asString() + WILL_SIGNAL_KEY_POSTFIX);
                Flyweight willSignalKafkaDataEx = kafkaDataExRW
                    .wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(kafkaTypeId)
                    .merged(m -> m
                        .deferred(0)
                        .timestamp(now().toEpochMilli())
                        .partition(p -> p.partitionId(-1).partitionOffset(-1))
                        .key(b -> b.length(willSignalKey.length())
                            .value(willSignalKey.value(), 0, willSignalKey.length()))
                        .hashKey(b -> b.length(delegate.clientId.length())
                            .value(delegate.clientId.value(), 0, delegate.clientId.length()))
                        .headersItem(h ->
                            h.nameLen(TYPE_HEADER_NAME.length())
                                .name(TYPE_HEADER_NAME.value(), 0, TYPE_HEADER_NAME.length())
                                .valueLen(WILL_SIGNAL_NAME.length())
                                .value(WILL_SIGNAL_NAME.value(), 0, WILL_SIGNAL_NAME.length())))
                    .build();

                doKafkaData(traceId, authorization, 0, 0, DATA_FLAG_COMPLETE,
                    null, willSignalKafkaDataEx);
            }

            delegate.doMqttWindow(authorization, traceId, budgetId, padding, capabilities);

            //            if (isSetWillFlag(delegate.flags) && initialSeq != 0 && initialSeq == initialAck)
            //            {
            //                delegate.doMqttData(traceId, authorization, budgetId, 0, DATA_FLAG_COMPLETE, EMPTY_OCTETS);
            //            }

            if (!sessionReady)
            {
                delegate.doMqttData(traceId, authorization, budgetId, 0, DATA_FLAG_COMPLETE, EMPTY_OCTETS);
                sessionReady = true;
            }
        }

        @Override
        protected void onKafkaFlush(
            FlushFW flush)
        {
            final long sequence = flush.sequence();
            final long acknowledge = flush.acknowledge();
            final long traceId = flush.traceId();
            final long authorization = flush.authorization();
            final long budgetId = flush.budgetId();
            final int reserved = flush.reserved();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;

            assert replyAck <= replySeq;

            if (!isSetWillFlag(delegate.flags))
            {
                delegate.doMqttData(traceId, authorization, budgetId, 0, DATA_FLAG_COMPLETE, EMPTY_OCTETS);
                sessionReady = true;
            }
        }

        @Override
        protected void onKafkaEnd(
            EndFW end)
        {
            final long sequence = end.sequence();
            final long acknowledge = end.acknowledge();
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

            delegate.doMqttEnd(traceId, authorization);
        }
    }

    private final class KafkaFetchWillSignalProxy extends KafkaSessionProxy
    {
        private KafkaFetchWillSignalProxy(
            long originId,
            long routedId,
            MqttSessionProxy delegate)
        {
            super(originId, routedId, delegate);
        }

        @Override
        protected void doKafkaBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            if (!MqttKafkaState.initialOpening(state))
            {
                state = MqttKafkaState.openingInitial(state);

                kafka = newKafkaStream(super::onKafkaMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                    traceId, authorization, affinity, delegate.sessionsTopic, delegate.clientId);
            }
        }

        @Override
        protected void handleKafkaData(
            DataFW data)
        {
            final OctetsFW extension = data.extension();
            final OctetsFW payload = data.payload();
            final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
            final KafkaDataExFW kafkaDataEx =
                dataEx != null && dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
            final KafkaMergedDataExFW kafkaMergedDataEx =
                kafkaDataEx != null && kafkaDataEx.kind() == KafkaDataExFW.KIND_MERGED ? kafkaDataEx.merged() : null;
            final KafkaKeyFW key = kafkaMergedDataEx != null ? kafkaMergedDataEx.key() : null;

            if (key != null)
            {
                MqttWillSignalFW willMessage =
                    mqttWillSignalRO.tryWrap(payload.buffer(), payload.offset(), payload.limit());
                delegate.lifetimeId = willMessage.lifetimeId().asString();
            }
        }

        @Override
        protected void onKafkaFlush(
            FlushFW flush)
        {
            final long sequence = flush.sequence();
            final long acknowledge = flush.acknowledge();
            final long traceId = flush.traceId();
            final long authorization = flush.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;

            assert replyAck <= replySeq;

            delegate.session.doKafkaEnd(traceId, authorization);
            final long routedId = delegate.session.routedId;

            delegate.session = new KafkaSessionSignalProxy(originId, routedId, delegate);
            delegate.session.doKafkaBeginIfNecessary(traceId, authorization, 0);
        }
    }

    private final class KafkaGroupProxy
    {
        private MessageConsumer kafka;
        private final long originId;
        private final long routedId;
        private final long initialId;
        private final long replyId;
        private final MqttSessionProxy delegate;

        private int state;

        private long initialSeq;
        private long initialAck;
        private int initialMax;

        private long replySeq;
        private long replyAck;
        private int replyMax;
        private int replyPad;

        private KafkaGroupProxy(
            long originId,
            long routedId,
            MqttSessionProxy delegate)
        {
            this.originId = originId;
            this.routedId = routedId;
            this.delegate = delegate;
            this.initialId = supplyInitialId.applyAsLong(routedId);
            this.replyId = supplyReplyId.applyAsLong(initialId);
        }

        private void doKafkaBegin(
            long traceId,
            long authorization,
            long affinity)
        {
            initialSeq = delegate.initialSeq;
            initialAck = delegate.initialAck;
            initialMax = delegate.initialMax;
            state = MqttKafkaState.openingInitial(state);

            kafka = newGroupStream(this::onGroupMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, affinity, delegate.clientId, delegate.sessionExpiryMillis);
        }

        private void doKafkaFlush(
            long traceId,
            long authorization,
            long budgetId,
            int reserved)
        {
            initialSeq = delegate.initialSeq;

            doFlush(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax,
                traceId, authorization, budgetId, reserved);
        }

        private void doKafkaEnd(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                initialSeq = delegate.initialSeq;
                initialAck = delegate.initialAck;
                initialMax = delegate.initialMax;
                state = MqttKafkaState.closeInitial(state);

                doEnd(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void doKafkaAbort(
            long traceId,
            long authorization)
        {
            if (!MqttKafkaState.initialClosed(state))
            {
                initialSeq = delegate.initialSeq;
                initialAck = delegate.initialAck;
                initialMax = delegate.initialMax;
                state = MqttKafkaState.closeInitial(state);

                doAbort(kafka, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization);
            }
        }

        private void onGroupMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onKafkaBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onKafkaData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onKafkaEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onKafkaAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onKafkaReset(reset);
                break;
            }
        }

        private void onKafkaBegin(
            BeginFW begin)
        {
            final long sequence = begin.sequence();
            final long acknowledge = begin.acknowledge();
            final int maximum = begin.maximum();
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();
            final long affinity = begin.affinity();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;
            assert acknowledge >= replyAck;

            replySeq = sequence;
            replyAck = acknowledge;
            replyMax = maximum;
            state = MqttKafkaState.openingReply(state);

            assert replyAck <= replySeq;

            delegate.doMqttBegin(traceId, authorization, affinity);
            doKafkaWindow(traceId, authorization, 0, 0, 0);
        }

        private void onKafkaData(
            DataFW data)
        {
            final long sequence = data.sequence();
            final long acknowledge = data.acknowledge();
            final long traceId = data.traceId();
            final long authorization = data.authorization();
            final long budgetId = data.budgetId();
            final int reserved = data.reserved();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence + reserved;

            assert replyAck <= replySeq;
            if (replySeq > replyAck + replyMax)
            {
                doKafkaReset(traceId);
                delegate.doMqttAbort(traceId, authorization);
            }
            else
            {
                final OctetsFW extension = data.extension();
                final ExtensionFW dataEx = extension.get(extensionRO::tryWrap);
                final KafkaDataExFW kafkaDataEx =
                    dataEx != null && dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
                final KafkaGroupDataExFW kafkaGroupDataEx =
                    kafkaDataEx != null && kafkaDataEx.kind() == KafkaDataExFW.KIND_GROUP ? kafkaDataEx.group() : null;
                final String16FW leaderId = kafkaGroupDataEx != null ? kafkaGroupDataEx.leaderId() : null;
                final String16FW memberId  = kafkaGroupDataEx != null ? kafkaGroupDataEx.memberId() : null;
                final int members  = kafkaGroupDataEx != null ? kafkaGroupDataEx.members() : 0;

                if (leaderId.equals(memberId))
                {
                    if (members > 1)
                    {
                        delegate.session.sendMigrateSignal(authorization, traceId);
                        delegate.session.sendWillSignal(authorization, traceId);
                        delegate.session.doKafkaEnd(traceId, authorization);
                        doKafkaEnd(traceId, authorization);
                    }
                    else
                    {
                        delegate.session.doKafkaEnd(traceId, authorization);
                        final long routedId = delegate.session.routedId;
                        delegate.session = new KafkaSessionStateProxy(originId, routedId, delegate);
                        delegate.session.doKafkaBeginIfNecessary(traceId, authorization, 0);
                    }
                }
            }
        }

        private void onKafkaEnd(
            EndFW end)
        {
            final long sequence = end.sequence();
            final long acknowledge = end.acknowledge();
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

            delegate.doMqttEnd(traceId, authorization);
        }

        private void onKafkaAbort(
            AbortFW abort)
        {
            final long sequence = abort.sequence();
            final long acknowledge = abort.acknowledge();
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();

            assert acknowledge <= sequence;
            assert sequence >= replySeq;

            replySeq = sequence;
            state = MqttKafkaState.closeReply(state);

            assert replyAck <= replySeq;

            delegate.doMqttAbort(traceId, authorization);
        }

        private void onKafkaReset(
            ResetFW reset)
        {
            final long sequence = reset.sequence();
            final long acknowledge = reset.acknowledge();
            final long traceId = reset.traceId();

            assert acknowledge <= sequence;
            assert acknowledge >= delegate.initialAck;

            delegate.initialAck = acknowledge;

            assert delegate.initialAck <= delegate.initialSeq;

            delegate.doMqttReset(traceId);
        }

        private void doKafkaReset(
            long traceId)
        {
            if (!MqttKafkaState.replyClosed(state))
            {
                state = MqttKafkaState.closeReply(state);

                doReset(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId);
            }
        }

        private void doKafkaWindow(
            long traceId,
            long authorization,
            long budgetId,
            int padding,
            int capabilities)
        {
            replyAck = delegate.replyAck;
            replyMax = delegate.replyMax;
            replyPad = delegate.replyPad;

            doWindow(kafka, originId, routedId, replyId, replySeq, replyAck, replyMax,
                traceId, authorization, budgetId, padding, replyPad, capabilities);
        }
    }


    private void doBegin(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doData(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int flags,
        int reserved,
        OctetsFW payload,
        Flyweight extension)
    {
        final DataFW frame = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .flags(flags)
            .budgetId(budgetId)
            .reserved(reserved)
            .payload(payload)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(frame.typeId(), frame.buffer(), frame.offset(), frame.sizeof());
    }
    private void doData(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int flags,
        int reserved,
        DirectBuffer buffer,
        int index,
        int length,
        Flyweight extension)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .flags(flags)
            .budgetId(budgetId)
            .reserved(reserved)
            .payload(buffer, index, length)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doEnd(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doAbort(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doFlush(
        MessageConsumer receiver,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int reserved)
    {
        final FlushFW flush = flushRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .budgetId(budgetId)
            .reserved(reserved)
            .build();

        receiver.accept(flush.typeId(), flush.buffer(), flush.offset(), flush.sizeof());
    }

    private MessageConsumer newKafkaStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        String16FW sessionsTopicName,
        String16FW clientId,
        String16FW clientIdMigrate,
        String16FW sessionId)
    {
        final KafkaBeginExFW kafkaBeginEx =
            kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m ->
                {
                    m.capabilities(c -> c.set(KafkaCapabilities.PRODUCE_AND_FETCH));
                    m.topic(sessionsTopicName);
                    m.groupId(MQTT_CLIENTS_GROUP_ID);
                    if (clientId != null)
                    {
                        m.partitionsItem(p ->
                            p.partitionId(KafkaOffsetType.HISTORICAL.value())
                                .partitionOffset(KafkaOffsetType.HISTORICAL.value()));
                        m.filtersItem(f -> f.conditionsItem(ci ->
                            ci.key(kb -> kb.length(clientId.length())
                            .value(clientId.value(), 0, clientId.length()))));
                    }
                    m.filtersItem(f ->
                    {
                        f.conditionsItem(ci ->
                            ci.key(kb -> kb.length(clientIdMigrate.length())
                                .value(clientIdMigrate.value(), 0, clientIdMigrate.length())));
                        f.conditionsItem(i -> i.not(n -> n.condition(c -> c.header(h ->
                            h.nameLen(SENDER_ID_NAME.length())
                                .name(SENDER_ID_NAME.value(), 0, SENDER_ID_NAME.length())
                                .valueLen(sessionId.length())
                                .value(sessionId.value(), 0, sessionId.length())))));
                    });
                    m.ackMode(b -> b.set(KAFKA_DEFAULT_ACK_MODE));
                })
                .build();


        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(kafkaBeginEx.buffer(), kafkaBeginEx.offset(), kafkaBeginEx.sizeof())
            .build();

        MessageConsumer receiver =
            streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }

    private MessageConsumer newKafkaStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        String16FW topic)
    {
        final KafkaBeginExFW kafkaBeginEx =
            kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m -> m.capabilities(c -> c.set(KafkaCapabilities.PRODUCE_ONLY))
                    .topic(topic)
                    .partitionsItem(p -> p.partitionId(-1).partitionOffset(-2L))
                    .ackMode(b -> b.set(KAFKA_DEFAULT_ACK_MODE)))
                .build();


        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(kafkaBeginEx.buffer(), kafkaBeginEx.offset(), kafkaBeginEx.sizeof())
            .build();

        MessageConsumer receiver =
            streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }

    private MessageConsumer newKafkaStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        String16FW topic,
        String16FW clientId)
    {
        //TODO: check in the new code what needs to be string/string16fw and the use of asString()
        String16FW key = new String16FW(clientId.asString() + WILL_SIGNAL_KEY_POSTFIX);
        final KafkaBeginExFW kafkaBeginEx =
            kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m ->
                    m.capabilities(c -> c.set(KafkaCapabilities.FETCH_ONLY))
                        .topic(topic)
                        .groupId(MQTT_CLIENTS_GROUP_ID)
                        .partitionsItem(p ->
                            p.partitionId(KafkaOffsetType.HISTORICAL.value())
                                .partitionOffset(KafkaOffsetType.HISTORICAL.value()))
                        .filtersItem(f ->
                            f.conditionsItem(c ->
                                c.key(k -> k.length(key.length())
                                    .value(key.value(), 0, key.length()))))
                        .evaluation(b -> b.set(KafkaEvaluation.EAGER)))
                .build();


        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(kafkaBeginEx.buffer(), kafkaBeginEx.offset(), kafkaBeginEx.sizeof())
            .build();

        MessageConsumer receiver =
            streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }

    private MessageConsumer newKafkaStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        String16FW clientId,
        String16FW lifetimeId,
        String16FW topic)
    {
        String16FW key = new String16FW(clientId.asString() + WILL_KEY_POSTFIX + lifetimeId.asString());
        final KafkaBeginExFW kafkaBeginEx =
            kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m ->
                    m.capabilities(c -> c.set(KafkaCapabilities.FETCH_ONLY))
                        .topic(topic)
                        .partitionsItem(p ->
                            p.partitionId(KafkaOffsetType.HISTORICAL.value())
                                .partitionOffset(KafkaOffsetType.HISTORICAL.value()))
                        .filtersItem(f ->
                            f.conditionsItem(c ->
                                c.key(k -> k.length(key.length())
                                    .value(key.value(), 0, key.length()))))
                        .evaluation(b -> b.set(KafkaEvaluation.EAGER)))
                .build();


        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(kafkaBeginEx.buffer(), kafkaBeginEx.offset(), kafkaBeginEx.sizeof())
            .build();

        MessageConsumer receiver =
            streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }


    private MessageConsumer newWillStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        String16FW sessionsTopicName)
    {
        final KafkaBeginExFW kafkaBeginEx =
            kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .merged(m ->
                    m.capabilities(c -> c.set(KafkaCapabilities.PRODUCE_AND_FETCH))
                        .topic(sessionsTopicName)
                        .groupId(MQTT_CLIENTS_GROUP_ID)
                        .filtersItem(f ->
                            f.conditionsItem(c -> c.header(h ->
                                h.nameLen(TYPE_HEADER_NAME.length())
                                    .name(TYPE_HEADER_NAME.value(), 0, TYPE_HEADER_NAME.length())
                                    .valueLen(WILL_SIGNAL_NAME.length())
                                    .value(WILL_SIGNAL_NAME.value(), 0, WILL_SIGNAL_NAME.length()))))
                        .ackMode(b -> b.set(KAFKA_DEFAULT_ACK_MODE)))
                .build();

        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(kafkaBeginEx.buffer(), kafkaBeginEx.offset(), kafkaBeginEx.sizeof())
            .build();

        MessageConsumer receiver =
            streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }

    private MessageConsumer newGroupStream(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long affinity,
        String16FW clientId,
        int sessionExpiryMs)
    {
        final KafkaBeginExFW kafkaBeginEx =
            kafkaBeginExRW.wrap(writeBuffer, BeginFW.FIELD_OFFSET_EXTENSION, writeBuffer.capacity())
                .typeId(kafkaTypeId)
                .group(g -> g.groupId(clientId).protocol(GROUP_PROTOCOL).timeout(sessionExpiryMs))
                .build();


        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(kafkaBeginEx.buffer(), kafkaBeginEx.offset(), kafkaBeginEx.sizeof())
            .build();

        MessageConsumer receiver =
            streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender);

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());

        return receiver;
    }

    private void doWindow(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId,
        long authorization,
        long budgetId,
        int padding,
        int minimum,
        int capabilities)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .budgetId(budgetId)
            .padding(padding)
            .minimum(minimum)
            .capabilities(capabilities)
            .build();

        sender.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doReset(
        MessageConsumer sender,
        long originId,
        long routedId,
        long streamId,
        long sequence,
        long acknowledge,
        int maximum,
        long traceId)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .build();

        sender.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }
}
