/*
 * Copyright 2017-2024 original authors
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
package io.micronaut.oraclecloud.httpclient.apache.core;

import com.oracle.bmc.http.client.ClientProperty;
import com.oracle.bmc.http.client.HttpClient;
import com.oracle.bmc.http.client.HttpClientBuilder;
import com.oracle.bmc.http.client.RequestInterceptor;
import com.oracle.bmc.http.client.StandardClientProperties;
import io.micronaut.core.annotation.Internal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

@Internal
final class ApacheCoreHttpClientBuilder implements HttpClientBuilder {
    static final String SOCKET_PATH_PROPERTY = "io.micronaut.oraclecloud.httpclient.apache.socket-path";

    static final Logger LOG = LoggerFactory.getLogger(ApacheCoreHttpClientBuilder.class);

    final ApacheCoreHttpProvider provider;
    final Collection<PrioritizedInterceptor> requestInterceptors = new ArrayList<>();
    URI baseUri;
    boolean buffered = true;
    Path socketPath;

    ApacheCoreHttpClientBuilder(ApacheCoreHttpProvider provider) {
        this.provider = provider;
        String socketPathProperty = System.getProperty(SOCKET_PATH_PROPERTY);
        if (socketPathProperty != null) {
            socketPath = Path.of(socketPathProperty);
        }
    }

    @Override
    public HttpClientBuilder baseUri(URI uri) {
        this.baseUri = Objects.requireNonNull(uri, "baseUri");
        return this;
    }

    @Override
    public HttpClientBuilder baseUri(String uri) {
        this.baseUri = URI.create(Objects.requireNonNull(uri, "baseUri"));
        return this;
    }

    @Override
    public <T> HttpClientBuilder property(ClientProperty<T> key, T value) {
        if (key == StandardClientProperties.BUFFER_REQUEST) {
            buffered = (Boolean) value;
        } else if (key == ApacheCoreHttpProvider.SOCKET_PATH) {
            socketPath = (Path) value;
        } else if (key == StandardClientProperties.READ_TIMEOUT
            || key == StandardClientProperties.CONNECT_TIMEOUT
            || key == StandardClientProperties.ASYNC_POOL_SIZE
        ) {
            // Those properties are set in by unmanaged clients sometimes
            LOG.debug("Attempted to set standard client property '{}' that is not supported for apache core client.", key.getName());
        } else {
            throw new IllegalArgumentException("Unknown or unsupported HTTP client property " + key);
        }
        return this;
    }

    @Override
    public HttpClientBuilder registerRequestInterceptor(int priority, RequestInterceptor interceptor) {
        Objects.requireNonNull(interceptor, "interceptor");
        requestInterceptors.add(new PrioritizedInterceptor(priority, interceptor));
        return this;
    }

    @Override
    public HttpClient build() {
        return new ApacheCoreHttpClient(this);
    }

    record PrioritizedInterceptor(int priority, RequestInterceptor interceptor) {
    }
}
