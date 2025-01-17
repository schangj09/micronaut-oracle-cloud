/*
 * Copyright 2017-2022 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.oraclecloud.httpclient.netty;

import com.fasterxml.jackson.core.JacksonException;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.http.client.ClientProperty;
import com.oracle.bmc.http.client.HttpClient;
import com.oracle.bmc.http.client.HttpRequest;
import com.oracle.bmc.http.client.Method;
import com.oracle.bmc.http.client.RequestInterceptor;
import com.oracle.bmc.http.client.StandardClientProperties;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.order.OrderUtil;
import io.micronaut.http.client.DefaultHttpClientConfiguration;
import io.micronaut.http.client.HttpVersionSelection;
import io.micronaut.http.client.RawHttpClient;
import io.micronaut.http.client.exceptions.ResponseClosedException;
import io.micronaut.http.client.netty.ConnectionManager;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.json.JsonMapper;
import io.micronaut.oraclecloud.serde.OciSdkMicronautSerializer;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.PrematureChannelClosureException;
import io.netty.handler.timeout.ReadTimeoutException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static io.micronaut.oraclecloud.httpclient.netty.NettyClientProperties.OCI_NETTY_CLIENT_FILTERS_KEY;

@Internal
final class NettyHttpClient implements HttpClient {
    /**
     * Default settings of {@link ClientConfiguration}. They are set by BaseClient,
     * so we ignore them if they are the default value.
     */
    private static final Map<ClientProperty<?>, Object> EXPECTED_PROPERTIES;

    private static final boolean LEGACY_NETTY_CLIENT = Boolean.getBoolean("io.micronaut.oraclecloud.httpclient.netty.legacy-netty-client");

    final boolean legacyNettyClient;
    final boolean hasContext;
    final boolean ownsThreadPool;
    final URI baseUri;
    final List<RequestInterceptor> requestInterceptors;
    final List<OciNettyClientFilter<?>> nettyClientFilter;
    final ExecutorService blockingIoExecutor;
    final String host;
    final int port;
    final boolean buffered;
    final ConnectionManager connectionManager;
    final RawHttpClient upstreamHttpClient;
    final DefaultHttpClient.RequestKey requestKey;
    final JsonMapper jsonMapper;

    static {
        ClientConfiguration cfg = ClientConfiguration.builder().build();
        EXPECTED_PROPERTIES = Map.of(
            StandardClientProperties.CONNECT_TIMEOUT, Duration.ofMillis(cfg.getConnectionTimeoutMillis()),
            StandardClientProperties.READ_TIMEOUT, Duration.ofMillis(cfg.getReadTimeoutMillis()),
            StandardClientProperties.ASYNC_POOL_SIZE, cfg.getMaxAsyncThreads()
        );
    }

    NettyHttpClient(NettyHttpClientBuilder builder) {
        this.legacyNettyClient = LEGACY_NETTY_CLIENT || (builder.managedProvider != null && builder.managedProvider.configuration.legacyNettyClient());
        RawHttpClient mnClient;
        if (builder.managedProvider == null) {
            hasContext = false;
            ownsThreadPool = true;
            DefaultHttpClientConfiguration cfg = new DefaultHttpClientConfiguration();
            if (builder.properties.containsKey(StandardClientProperties.CONNECT_TIMEOUT)) {
                cfg.setConnectTimeout((Duration) builder.properties.get(StandardClientProperties.CONNECT_TIMEOUT));
            }
            if (builder.properties.containsKey(StandardClientProperties.READ_TIMEOUT)) {
                cfg.setReadTimeout((Duration) builder.properties.get(StandardClientProperties.READ_TIMEOUT));
            }
            mnClient = RawHttpClient.create(null, cfg);
            blockingIoExecutor = Executors.newCachedThreadPool();
            jsonMapper = OciSdkMicronautSerializer.getDefaultObjectMapper();
        } else {
            hasContext = true;
            for (Map.Entry<ClientProperty<?>, Object> entry : builder.properties.entrySet()) {
                if (!entry.getValue().equals(EXPECTED_PROPERTIES.get(entry.getKey())) && !entry.getKey().equals(OCI_NETTY_CLIENT_FILTERS_KEY)) {
                    throw new IllegalArgumentException("Cannot change property " + entry.getKey() + " in the managed netty HTTP client. Please configure this setting through the micronaut HTTP client configuration instead. The service ID for the netty client is '" + ManagedNettyHttpProvider.SERVICE_ID + "'.");
                }
            }
            if (builder.managedProvider.mnHttpClient != null) {
                mnClient = builder.managedProvider.mnHttpClient;
            } else {
                mnClient = builder.managedProvider.mnHttpClientRegistry.getRawClient(
                    HttpVersionSelection.forClientConfiguration(new DefaultHttpClientConfiguration()),
                    builder.serviceId,
                    null
                );
            }
            if (builder.managedProvider.ioExecutor == null) {
                ownsThreadPool = true;
                blockingIoExecutor = Executors.newCachedThreadPool();
            } else {
                ownsThreadPool = false;
                blockingIoExecutor = builder.managedProvider.ioExecutor;
            }
            jsonMapper = builder.managedProvider.jsonMapper;
        }
        upstreamHttpClient = mnClient;
        connectionManager = legacyNettyClient ? ((DefaultHttpClient) mnClient).connectionManager() : null;
        baseUri = Objects.requireNonNull(builder.baseUri, "baseUri");
        requestInterceptors = builder.requestInterceptors.stream()
            .sorted(Comparator.comparingInt(p -> p.priority))
            .map(p -> p.value)
            .collect(Collectors.toList());

        if (builder.properties.containsKey(OCI_NETTY_CLIENT_FILTERS_KEY)) {
            nettyClientFilter = ((List<OciNettyClientFilter<?>>) builder.properties.get(OCI_NETTY_CLIENT_FILTERS_KEY)).stream().sorted(OrderUtil.COMPARATOR).toList();
        } else {
            nettyClientFilter = Collections.emptyList();
        }

        requestKey = legacyNettyClient ? new DefaultHttpClient.RequestKey((DefaultHttpClient) mnClient, builder.baseUri) : null;
        this.port = builder.baseUri.getPort();
        this.host = builder.baseUri.getHost();
        this.buffered = builder.buffered;
    }

    ByteBufAllocator alloc() {
        return connectionManager == null ? ByteBufAllocator.DEFAULT : connectionManager.alloc();
    }

    @SuppressWarnings("deprecation")
    @Override
    public HttpRequest createRequest(Method method) {
        return legacyNettyClient ? new NettyHttpRequest(this, method) : new MicronautHttpRequest(this, method);
    }

    @Override
    public boolean isProcessingException(Exception e) {
        // these exceptions will allow the client to retry the request
        return e instanceof JacksonException ||
            e instanceof PrematureChannelClosureException ||
            e instanceof ReadTimeoutException ||
            e instanceof io.micronaut.http.client.exceptions.ReadTimeoutException ||
            e instanceof ResponseClosedException;
    }

    @Override
    public void close() {
        if (!hasContext) {
            try {
                upstreamHttpClient.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (ownsThreadPool) {
            blockingIoExecutor.shutdown();
        }
    }
}
