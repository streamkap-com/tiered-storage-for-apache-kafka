/*
 * Copyright 2023 Aiven Oy
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aiven.kafka.tieredstorage.chunkmanager.cache;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.kafka.common.TopicIdPartition;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentId;
import org.apache.kafka.server.log.remote.storage.RemoteLogSegmentMetadata;

import io.aiven.kafka.tieredstorage.chunkmanager.ChunkManager;
import io.aiven.kafka.tieredstorage.manifest.SegmentManifest;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.DOUBLE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Tests metrics gathering on Chunk Cache implementations
 */
@ExtendWith(MockitoExtension.class)
class ChunkCacheMetricsTest {
    static final MBeanServer MBEAN_SERVER = ManagementFactory.getPlatformMBeanServer();

    public static final Uuid SEGMENT_ID = Uuid.randomUuid();

    static final int LOG_SEGMENT_BYTES = 10;
    static final RemoteLogSegmentMetadata REMOTE_LOG_SEGMENT_METADATA =
        new RemoteLogSegmentMetadata(
            new RemoteLogSegmentId(
                new TopicIdPartition(Uuid.randomUuid(), new TopicPartition("topic", 0)),
                SEGMENT_ID),
            1, -1, -1, -1, 1L,
            LOG_SEGMENT_BYTES, Collections.singletonMap(1, 100L));

    @TempDir
    static Path baseCachePath;

    @Mock
    ChunkManager chunkManager;
    @Mock
    SegmentManifest segmentManifest;

    private static Stream<Arguments> caches() {
        return Stream.of(
            Arguments.of(
                DiskBasedChunkCache.class,
                Map.of(
                    "retention.ms", "-1",
                    "size", "-1",
                    "path", baseCachePath.toString()
                )
            ),
            Arguments.of(
                InMemoryChunkCache.class,
                Map.of(
                    "retention.ms", "-1",
                    "size", "-1"
                )
            ));
    }

    @ParameterizedTest(name = "Cache {0}")
    @MethodSource("caches")
    void shouldRecordMetrics(final Class<ChunkCache<?>> chunkCacheClass, final Map<String, ?> config)
        throws Exception {
        // Given a chunk cache implementation
        when(chunkManager.getChunk(any(), any(), anyInt()))
            .thenReturn(new ByteArrayInputStream("test".getBytes()));

        final var chunkCache = chunkCacheClass.getDeclaredConstructor(ChunkManager.class).newInstance(chunkManager);
        chunkCache.configure(config);

        // When getting a existing chunk from cache
        chunkCache.getChunk(REMOTE_LOG_SEGMENT_METADATA, segmentManifest, 0);
        chunkCache.getChunk(REMOTE_LOG_SEGMENT_METADATA, segmentManifest, 0);

        // Then the following metrics should be available
        final var objectName = new ObjectName("aiven.kafka.server.tieredstorage.cache:type=chunk-cache");
        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-hits-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-misses-total"))
            .isEqualTo(1.0);
        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-load-success-time-total"))
            .asInstanceOf(DOUBLE)
            .isGreaterThan(0);

        // compute is considered as load success regardless if present or not
        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-load-success-total"))
            .isEqualTo(2.0);
        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-load-failure-time-total"))
            .isEqualTo(0.0);

        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-load-failure-total"))
            .isEqualTo(0.0);

        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-eviction-total"))
            .isEqualTo(0.0);
        assertThat(MBEAN_SERVER.getAttribute(objectName, "cache-eviction-weight-total"))
            .isEqualTo(0.0);
    }
}
