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

import com.oracle.bmc.http.client.Serializer;
import io.micronaut.core.annotation.Internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Extension to {@link Serializer} for blocking reads/writes.
 */
@Internal
sealed interface ApacheCoreSerializer extends Serializer permits JacksonSerializer, SerdeSerializer {
    <T> T readValue(InputStream inputStream, Class<T> type) throws IOException;

    <T> List<T> readList(InputStream inputStream, Class<T> type) throws IOException;

    void writeValue(OutputStream outputStream, Object value) throws IOException;
}
