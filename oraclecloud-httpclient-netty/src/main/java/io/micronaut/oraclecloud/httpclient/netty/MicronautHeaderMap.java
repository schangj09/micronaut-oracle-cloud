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

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.MutableHttpHeaders;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * {@link java.util.Map} wrapper around micronaut {@link HttpHeaders}.
 */
@Internal
final class MicronautHeaderMap extends AbstractMap<String, List<String>> {
    private final HttpHeaders headers;

    MicronautHeaderMap(HttpHeaders headers) {
        this.headers = headers;
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return new AbstractSet<Entry<String, List<String>>>() {
            @Override
            public Iterator<Entry<String, List<String>>> iterator() {
                return new HeaderIterator(headers);
            }

            @Override
            public int size() {
                return headers.names().size();
            }
        };
    }

    @Override
    public List<String> get(Object key) {
        if (!(key instanceof String)) {
            return null;
        }
        List<String> found = headers.getAll((String) key);
        return found.isEmpty() ? null : found;
    }

    @Override
    public List<String> remove(Object key) {
        if (!(key instanceof String s)) {
            return null;
        }
        List<String> items = headers.getAll(s);
        ((MutableHttpHeaders) headers).remove(s);
        return items.isEmpty() ? null : items; // follow remove() contract
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String s && headers.contains(s);
    }

    @Override
    public Set<String> keySet() {
        return new KeySet();
    }

    private final class KeySet extends AbstractSet<String> {
        @Override
        public boolean contains(Object o) {
            return containsKey(o);
        }

        @Override
        public boolean remove(Object o) {
            return MicronautHeaderMap.this.remove(o) != null;
        }

        @Override
        public Iterator<String> iterator() {
            return headers.names().iterator();
        }

        @Override
        public int size() {
            return headers.names().size();
        }
    }

    private static final class HeaderIterator implements Iterator<Entry<String, List<String>>> {
        final HttpHeaders headers;
        final Iterator<String> keyItr;

        HeaderIterator(HttpHeaders headers) {
            this.headers = headers;
            keyItr = headers.names().iterator();
        }

        @Override
        public boolean hasNext() {
            return keyItr.hasNext();
        }

        @Override
        public Entry<String, List<String>> next() {
            String key = keyItr.next();
            return new SimpleEntry<>(key, headers.getAll(key));
        }
    }
}
