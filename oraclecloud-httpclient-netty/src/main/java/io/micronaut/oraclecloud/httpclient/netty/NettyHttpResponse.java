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

import com.oracle.bmc.http.client.HttpResponse;
import io.micronaut.core.type.Argument;
import io.micronaut.json.JsonMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

@Deprecated
final class NettyHttpResponse implements HttpResponse {
    private final JsonMapper jsonMapper;
    private final io.netty.handler.codec.http.HttpResponse nettyResponse;
    private final LimitedBufferingBodyHandler limitedBufferingBodyHandler;
    private final UndecidedBodyHandler undecidedBodyHandler;
    private final Executor offloadExecutor;

    NettyHttpResponse(JsonMapper jsonMapper, io.netty.handler.codec.http.HttpResponse nettyResponse, LimitedBufferingBodyHandler limitedBufferingBodyHandler, UndecidedBodyHandler undecidedBodyHandler, Executor offloadExecutor) {
        this.jsonMapper = jsonMapper;
        this.nettyResponse = nettyResponse;
        this.limitedBufferingBodyHandler = limitedBufferingBodyHandler;
        this.undecidedBodyHandler = undecidedBodyHandler;
        this.offloadExecutor = offloadExecutor;
    }

    @Override
    public int status() {
        return nettyResponse.status().code();
    }

    @Override
    public Map<String, List<String>> headers() {
        return new HeaderMap(nettyResponse.headers());
    }

    @Override
    public CompletionStage<InputStream> streamBody() {
        return undecidedBodyHandler.asInputStream();
    }

    /**
     * Get the body as a buffer, falling back to {@link LimitedBufferingBodyHandler} if the body has already been
     * requested previously as another type.
     */
    private CompletableFuture<ByteBuf> bodyAsBuffer() {
        CompletableFuture<ByteBuf> buffer;
        if (undecidedBodyHandler.hasDecided()) {
            buffer = limitedBufferingBodyHandler.getFuture().thenApply(ByteBuf::retain);
        } else {
            buffer = undecidedBodyHandler.asBuffer();
        }
        return buffer;
    }

    @Override
    public <T> CompletionStage<T> body(Class<T> type) {
        return thenApply(bodyAsBuffer(), buf -> {
            try {
                if (!buf.isReadable()) {
                    /* This is a bit weird. jax-rs Response.readEntity says:
                     * "for a zero-length response entities returns a corresponding Java object
                     * that represents zero-length data."
                     * This appears to refer to types like byte[] and String, which return an empty
                     * array or string when the body is empty.
                     *
                     * For complex types, this behavior comes from jackson, and is explicitly
                     * against the jax-rs standard:
                     * https://github.com/FasterXML/jackson-jaxrs-providers/issues/49
                     * Basically, by default (which oci-sdk uses), jackson returns null when the
                     * body is empty.
                     *
                     * We replicate the jackson behavior here. We don't replicate the behavior for
                     * byte[] and String, those should usually go through textBody or other body
                     * methods anyway.
                     */
                    return null;
                }

                return jsonMapper.readValue(new ByteBufInputStream(buf), type);
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                buf.release();
            }
        });
    }

    @Override
    public <T> CompletionStage<List<T>> listBody(Class<T> type) {
        Argument<List<T>> listArgument = Argument.listOf(type);
        return thenApply(bodyAsBuffer(), buf -> {
            try {
                return jsonMapper.readValue(new ByteBufInputStream(buf), listArgument);
            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                buf.release();
            }
        });
    }

    @Override
    public CompletionStage<String> textBody() {
        return thenApply(bodyAsBuffer(), buf -> {
            try {
                return buf.toString(StandardCharsets.UTF_8);
            } finally {
                buf.release();
            }
        });
    }

    private <T, U> CompletionStage<U> thenApply(CompletionStage<T> stage, Function<? super T, ? extends U> fn) {
        if (offloadExecutor == null) {
            return stage.thenApply(fn);
        } else {
            return stage.thenApplyAsync(fn, offloadExecutor);
        }
    }

    @Override
    public void close() {
        if (!undecidedBodyHandler.hasDecided()) {
            undecidedBodyHandler.discard();
        }
    }
}
