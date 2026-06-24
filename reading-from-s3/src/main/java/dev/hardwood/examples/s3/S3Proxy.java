/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.s3;

import java.nio.file.Path;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.MountableFile;

/// A throwaway, S3-compatible server for this example, run as a Docker container via
/// [Testcontainers](https://testcontainers.com). It stands in for Amazon S3 (or MinIO) so the whole
/// example runs offline with no AWS account — Hardwood speaks the same S3 protocol to it as to the
/// real thing.
///
/// It runs the [s3proxy](https://github.com/gaul/s3proxy) image with the `filesystem` backend, which
/// serves whatever objects live under `/data`. The bucket is seeded *before startup* by copying the
/// dataset into `/data/{bucket}/{key}` for each key. It needs a running Docker daemon; Testcontainers
/// manages the container's lifecycle and removes it on [#close].
///
/// This is *infrastructure for the demo*, not something you'd write in an app — your data already
/// lives in S3. It exists only so a bare `./mvnw compile exec:java` has something to read from.
public final class S3Proxy implements AutoCloseable {

    /// `andrewgaul/s3proxy` pinned to the s3proxy 3.1.0 release commit, mirrored to GHCR.
    private static final String IMAGE = "ghcr.io/hardwood-hq/s3proxy:sha-6597ca59cd5c5fa8ee313e13d349d507cc6090c3";

    /// The port s3proxy listens on inside the container; the host port is mapped dynamically.
    private static final int PORT = 80;

    private final GenericContainer<?> container;

    private S3Proxy(GenericContainer<?> container) {
        this.container = container;
    }

    /// Starts the server, authenticating requests with `accessKey`/`secretKey` over AWS Signature V4
    /// (the signing Hardwood uses), and uploads `file` once per entry in `keys` into `bucket`.
    public static S3Proxy start(String accessKey, String secretKey, String bucket, Path file,
                                String... keys) {
        GenericContainer<?> container = new GenericContainer<>(IMAGE)
                .withExposedPorts(PORT)
                .withEnv("S3PROXY_AUTHORIZATION", "aws-v2-or-v4")
                .withEnv("S3PROXY_IDENTITY", accessKey)
                .withEnv("S3PROXY_CREDENTIAL", secretKey)
                .withEnv("S3PROXY_ENDPOINT", "http://0.0.0.0:" + PORT)
                .withEnv("JCLOUDS_PROVIDER", "filesystem")
                .withEnv("JCLOUDS_FILESYSTEM_BASEDIR", "/data");

        // The filesystem backend turns each top-level directory under /data into a bucket and each
        // file into an object, so dropping the dataset there pre-populates the bucket. Streamed over
        // the Docker socket, this works even when the daemon is remote (e.g. Docker-in-Docker).
        for (String key : keys) {
            container.withCopyFileToContainer(MountableFile.forHostPath(file), "/data/" + bucket + "/" + key);
        }

        container.start();
        return new S3Proxy(container);
    }

    /// The base URL to hand to `S3Source.endpoint(...)`, e.g. `http://localhost:49317`.
    public String endpoint() {
        return "http://" + container.getHost() + ":" + container.getMappedPort(PORT);
    }

    @Override
    public void close() {
        container.stop();
    }
}
