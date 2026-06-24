/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.s3;

import dev.hardwood.Hardwood;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.s3.S3Credentials;
import dev.hardwood.s3.S3InputFile;
import dev.hardwood.s3.S3Source;
import dev.hardwood.schema.ColumnProjection;
import dev.hardwood.schema.FileSchema;

/// Read Parquet straight from Amazon S3 — no local copy, streaming only the bytes you need.
///
/// The same code works against any S3-compatible store; here it points at a [MinIO](https://min.io)
/// server running next to it in Docker so the whole thing runs offline, with no AWS account.
/// Switching to real Amazon S3 changes only the `S3Source` configuration — see the README's
/// "Pointing at real AWS" section.
///
/// `S3Source`: https://hardwood.dev/api/1.0.0.CR2/dev/hardwood/s3/S3Source.html
///
/// What it shows:
///   1. Build an `S3Source` for an S3-compatible endpoint (endpoint, path-style, credentials).
///   2. Open one object and read its schema and a few rows, exactly like a local file.
///   3. Report how many bytes and HTTP requests the read actually cost — Hardwood fetches
///      byte *ranges*, so it transfers far less than the whole file.
///   4. Open many objects in a bucket as one logical dataset with `Hardwood.openAll`.
///
/// How-to: https://hardwood.dev/latest/how-to/s3/
/// Reference: https://hardwood.dev/latest/reference/s3/
public final class Main {

    public static void main(String[] args) throws Exception {
        // Connection details come from the environment so the same build runs both inside the
        // Docker network (endpoint "http://minio:9000") and from your host ("http://localhost:9000").
        String endpoint = env("S3_ENDPOINT", "http://localhost:9000");
        String region = env("S3_REGION", "us-east-1");
        String accessKey = env("S3_ACCESS_KEY", "minioadmin");
        String secretKey = env("S3_SECRET_KEY", "minioadmin");
        String bucket = env("S3_BUCKET", "taxi");
        String key = env("S3_KEY", "yellow_tripdata_2026-01.parquet");

        // An S3Source holds the endpoint, credentials, and a shared HTTP client. Build it once and
        // reuse it for every object you open — that reuses connections and credentials. MinIO (and
        // most non-AWS services) need path-style addressing: "{endpoint}/{bucket}/{key}" rather than
        // the virtual-hosted "{bucket}.{endpoint}/{key}" that AWS uses by default.
        try (S3Source source = S3Source.builder()
                .endpoint(endpoint)
                .region(region)
                .pathStyle(true)
                .credentials(S3Credentials.of(accessKey, secretKey))
                .build()) {

            readOneObject(source, bucket, key);
            readWholeBucket(source, bucket, key);
        }
    }

    /// Open a single object and read it just like a local file — the only S3-specific part is
    /// `source.inputFile(...)` in place of `InputFile.of(path)`.
    private static void readOneObject(S3Source source, String bucket, String key) throws Exception {
        System.out.println("== Reading s3://" + bucket + "/" + key + " ==");

        // An S3InputFile reads byte ranges over signed HTTP GETs. Closing it releases the
        // connection; the counters on it tell us what the read cost over the wire.
        try (S3InputFile in = source.inputFile(bucket, key);
             ParquetFileReader reader = ParquetFileReader.open(in)) {

            // Metadata lives in the footer. Reading it pulls only the tail of the object, not the
            // whole file — already a win over downloading the object to read its shape.
            System.out.println("Total rows: " + reader.getFileMetaData().numRows());
            FileSchema schema = reader.getFileSchema();
            System.out.println("Columns: " + schema.getColumnCount());

            // Project just the two columns we care about and stop after five rows. With a
            // projection, Hardwood fetches only the byte ranges backing those columns — not the
            // other 18 — so the read stays tiny next to the full object.
            System.out.println("\nFirst 5 trips:");
            try (RowReader rows = reader.buildRowReader()
                    .projection(ColumnProjection.columns("VendorID", "trip_distance"))
                    .head(5)
                    .build()) {
                while (rows.hasNext()) {
                    rows.next();
                    System.out.printf("  vendor=%d  distance=%.2f mi%n",
                            rows.getInt("VendorID"),
                            rows.getDouble("trip_distance"));
                }
            }

            // The payoff of range reads: the counters cover the footer fetch plus the few pages
            // backing those two columns — a sliver of the multi-megabyte object. The same code over
            // real S3 transfers (and bills) only those bytes.
            System.out.printf("%nProjected 2 of %d columns — fetched %,d bytes over %d HTTP requests"
                            + " (the object itself is %,d bytes).%n%n",
                    schema.getColumnCount(), in.networkBytesFetched(), in.networkRequestCount(), in.length());
        }
    }

    /// Treat every matching object in the bucket as one logical dataset. `inputFilesInBucket`
    /// builds an S3InputFile per key, and `Hardwood.openAll` scans them with a shared thread pool
    /// that prefetches the next file's pages while the current one is still being read.
    private static void readWholeBucket(S3Source source, String bucket, String key) throws Exception {
        // The seed step uploads the same month twice (a plain key and a "-copy" key) so this part
        // has more than one object to stitch together without a second download.
        String copyKey = key.replace(".parquet", "-copy.parquet");
        System.out.println("== Reading the whole '" + bucket + "' bucket as one dataset ==");

        try (Hardwood hardwood = Hardwood.create();
             ParquetFileReader reader = hardwood.openAll(source.inputFilesInBucket(bucket, key, copyKey));
             RowReader rows = reader.buildRowReader()
                     .projection(ColumnProjection.columns("VendorID"))
                     .build()) {
            long count = 0;
            while (rows.hasNext()) {
                rows.next();
                count++;
            }
            System.out.printf("Scanned %,d rows across 2 objects.%n", count);
        }
    }

    /// Reads an environment variable, falling back to a default when it is unset or blank.
    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
