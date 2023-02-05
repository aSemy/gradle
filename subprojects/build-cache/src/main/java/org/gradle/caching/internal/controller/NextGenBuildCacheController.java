/*
 * Copyright 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.caching.internal.controller;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.io.Closer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.UnsynchronizedByteArrayInputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.gradle.caching.BuildCacheEntryWriter;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.DefaultBuildCacheKey;
import org.gradle.caching.internal.controller.CacheManifest.ManifestEntry;
import org.gradle.caching.internal.controller.service.BuildCacheLoadResult;
import org.gradle.caching.internal.origin.OriginMetadata;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.file.FileType;
import org.gradle.internal.file.TreeType;
import org.gradle.internal.hash.HashCode;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.internal.snapshot.RelativePathTracker;
import org.gradle.internal.snapshot.SnapshotUtil;
import org.gradle.internal.snapshot.SnapshotVisitResult;
import org.gradle.internal.vfs.FileSystemAccess;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class NextGenBuildCacheController implements BuildCacheController {
    // TODO Move all thread-local buffers to a shared service
    // TODO Make buffer size configurable
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final ThreadLocal<byte[]> COPY_BUFFERS = ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);

    private final NextGenBuildCacheAccess cacheAccess;
    private final FileSystemAccess fileSystemAccess;
    private final String buildInvocationId;
    private final Deleter deleter;
    private final Gson gson;

    public NextGenBuildCacheController(
        String buildInvocationId,
        Deleter deleter,
        FileSystemAccess fileSystemAccess,
        NextGenBuildCacheAccess cacheAccess
    ) {
        this.buildInvocationId = buildInvocationId;
        this.deleter = deleter;
        this.fileSystemAccess = fileSystemAccess;
        this.cacheAccess = cacheAccess;
        this.gson = new GsonBuilder()
            .registerTypeAdapter(Duration.class, new TypeAdapter<Duration>() {
                @Override
                public void write(JsonWriter out, Duration value) throws IOException {
                    out.value(value.toMillis());
                }

                @Override
                public Duration read(JsonReader in) throws IOException {
                    return Duration.ofMillis(in.nextLong());
                }
            })
            .registerTypeAdapter(HashCode.class, new TypeAdapter<HashCode>() {
                @Override
                public void write(JsonWriter out, HashCode value) throws IOException {
                    out.value(value.toString());
                }

                @Override
                public HashCode read(JsonReader in) throws IOException {
                    return HashCode.fromString(in.nextString());
                }
            })
            .create();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean isEmitDebugLogging() {
        return false;
    }

    @Override
    public Optional<BuildCacheLoadResult> load(BuildCacheKey manifestCacheKey, CacheableEntity cacheableEntity) {
        // TODO Make load() return T
        AtomicReference<CacheManifest> manifestRef = new AtomicReference<>();
        cacheAccess.load(Collections.singletonMap(manifestCacheKey, null), (manifestStream, __) ->
            manifestRef.set(gson.fromJson(new InputStreamReader(manifestStream), CacheManifest.class)));
        CacheManifest manifest = manifestRef.get();
        if (manifest == null) {
            return Optional.empty();
        }

        AtomicLong loadedEntryCount = new AtomicLong(0);
        ImmutableSortedMap.Builder<String, FileSystemSnapshot> snapshots = ImmutableSortedMap.naturalOrder();

        cacheableEntity.visitOutputTrees((propertyName, type, root) -> {
            // Invalidate VFS
            fileSystemAccess.write(Collections.singleton(root.getAbsolutePath()), () -> {});

            // TODO Apply diff to outputs instead of clearing them here and loading everything
            try {
                cleanOutputDirectory(type, root);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // Note that there can be multiple output files with the same content
            ImmutableListMultimap.Builder<BuildCacheKey, File> filesBuilder = ImmutableListMultimap.builder();
            manifest.getPropertyManifests().get(propertyName).forEach(entry -> {
                File file = new File(root, entry.getRelativePath());
                switch (entry.getType()) {
                    case Directory:
                        // TODO Handle this
                        //noinspection ResultOfMethodCallIgnored
                        file.mkdirs();
                        break;
                    case RegularFile:
                        filesBuilder.put(new DefaultBuildCacheKey(entry.getContentHash()), file);
                        break;
                    case Missing:
                        FileUtils.deleteQuietly(file);
                        break;
                }
            });

            // TODO Filter out entries that are already in the right place in the output directory
            cacheAccess.load(filesBuilder.build().asMap(), (input, filesForHash) -> {
                loadedEntryCount.addAndGet(filesForHash.size());

                try (Closer closer = Closer.create()) {
                    OutputStream output = filesForHash.stream()
                        .map(file -> {
                            try {
                                return closer.register(new FileOutputStream(file));
                            } catch (FileNotFoundException e) {
                                throw new UncheckedIOException("Couldn't create " + file.getAbsolutePath(), e);
                            }
                        })
                        .map(OutputStream.class::cast)
                        .reduce(TeeOutputStream::new)
                        .orElse(NullOutputStream.NULL_OUTPUT_STREAM);

                    IOUtils.copyLarge(input, output, COPY_BUFFERS.get());
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });

            // TODO Reuse the data in the manifest instead of re-reading just written files
            FileSystemLocationSnapshot snapshot = fileSystemAccess.read(root.getAbsolutePath());
            snapshots.put(propertyName, snapshot);
        });

        ImmutableSortedMap<String, FileSystemSnapshot> resultingSnapshots = snapshots.build();
        return Optional.of(new BuildCacheLoadResult() {
            @Override
            public long getArtifactEntryCount() {
                return loadedEntryCount.get();
            }

            @Override
            public OriginMetadata getOriginMetadata() {
                return manifest.getOriginMetadata();
            }

            @Override
            public ImmutableSortedMap<String, FileSystemSnapshot> getResultingSnapshots() {
                return resultingSnapshots;
            }
        });
    }

    @Override
    public void store(BuildCacheKey manifestCacheKey, CacheableEntity entity, Map<String, FileSystemSnapshot> snapshots, Duration executionTime) {
        ImmutableMap.Builder<String, List<ManifestEntry>> propertyManifests = ImmutableMap.builder();

        entity.visitOutputTrees((propertyName, type, root) -> {
            ImmutableList.Builder<ManifestEntry> manifestEntries = ImmutableList.builder();
            FileSystemSnapshot rootSnapshot = snapshots.get(propertyName);
            rootSnapshot.accept(new RelativePathTracker(), (snapshot, relativePath) -> {
                if (relativePath.isRoot()) {
                    assertCorrectType(type, snapshot);
                }
                manifestEntries.add(new ManifestEntry(
                    snapshot.getType(),
                    relativePath.toRelativePath(),
                    snapshot.getHash(),
                    SnapshotUtil.getLength(snapshot)));
                return SnapshotVisitResult.CONTINUE;
            });
            propertyManifests.put(propertyName, manifestEntries.build());
        });

        CacheManifest manifest = new CacheManifest(
            new OriginMetadata(buildInvocationId, executionTime),
            propertyManifests.build());

        entity.visitOutputTrees((propertyName, type, root) -> {
            Map<BuildCacheKey, ManifestEntry> manifestIndex = manifest.getPropertyManifests().get(propertyName).stream()
                .filter(entry -> entry.getType() == FileType.RegularFile)
                .collect(ImmutableMap.toImmutableMap(
                    manifestEntry -> new DefaultBuildCacheKey(manifestEntry.getContentHash()),
                    Function.identity(),
                    // When there are multiple identical files to store, it doesn't matter which one we read
                    (a, b) -> a)
                );

            cacheAccess.store(manifestIndex, manifestEntry -> new BuildCacheEntryWriter() {
                @Override
                public InputStream openStream() throws IOException {
                    return new FileInputStream(new File(root, manifestEntry.getRelativePath()));
                }

                @Override
                public void writeTo(OutputStream output) throws IOException {
                    try (InputStream input = openStream()) {
                        IOUtils.copyLarge(input, output, COPY_BUFFERS.get());
                    }
                }

                @Override
                public long getSize() {
                    return manifestEntry.getLength();
                }
            });
        });

        cacheAccess.store(Collections.singletonMap(manifestCacheKey, manifest), __ -> {
            String manifestJson = gson.toJson(manifest);
            byte[] bytes = manifestJson.getBytes(StandardCharsets.UTF_8);

            return new BuildCacheEntryWriter() {
                @Override
                public InputStream openStream() {
                    return new UnsynchronizedByteArrayInputStream(bytes);
                }

                @Override
                public void writeTo(OutputStream output) throws IOException {
                    output.write(bytes);
                }

                @Override
                public long getSize() {
                    return bytes.length;
                }
            };
        });
    }

    private static void assertCorrectType(TreeType type, FileSystemLocationSnapshot snapshot) {
        if (snapshot.getType() == FileType.Missing) {
            return;
        }
        switch (type) {
            case DIRECTORY:
                if (snapshot.getType() != FileType.Directory) {
                    throw new IllegalArgumentException(String.format("Expected '%s' to be a directory", snapshot.getAbsolutePath()));
                }
                break;
            case FILE:
                if (snapshot.getType() != FileType.RegularFile) {
                    throw new IllegalArgumentException(String.format("Expected '%s' to be a file", snapshot.getAbsolutePath()));
                }
                break;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public void close() throws IOException {
        cacheAccess.close();
    }

    // FIXME code duplicate
    private void cleanOutputDirectory(TreeType type, File root) throws IOException {
        switch (type) {
            case DIRECTORY:
                deleter.ensureEmptyDirectory(root);
                break;
            case FILE:
                if (!makeDirectory(root.getParentFile())) {
                    if (root.exists()) {
                        deleter.deleteRecursively(root);
                    }
                }
                break;
            default:
                throw new AssertionError();
        }
    }

    // FIXME code duplicate
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean makeDirectory(File target) throws IOException {
        if (target.isDirectory()) {
            return false;
        } else if (target.isFile()) {
            deleter.delete(target);
        }
        FileUtils.forceMkdir(target);
        return true;
    }

}
