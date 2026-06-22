/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.bytebuffersource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.FileSchema;

/// Read Parquet straight from an in-memory `ByteBuffer` — the "I already have the bytes" path.
///
/// Sometimes the Parquet bytes never touch the local filesystem: they arrive as an HTTP response
/// body, come back from a blob store (S3/GCS/Azure), or are simply a `byte[]` handed to your
/// service. `InputFile.of(ByteBuffer)` reads such bytes directly — since the data is already in
/// memory, `open()` is a no-op and nothing holds a file handle. `InputFile.ofBuffers(...)` extends
/// the same idea to several in-memory blobs read as one dataset, the in-memory counterpart of
/// `InputFile.ofPaths(...)`.
///
/// (Native HTTP(S) reading is not yet available upstream, so loading the bytes yourself into a
/// `ByteBuffer` is today's way to read remote Parquet.)
///
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
public final class Main {

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        // Simulate "I already have the bytes": pull the whole file into memory once. In a real
        // service these bytes would come from an HTTP body or a blob store; here we read them off
        // disk just to get a real Parquet payload, then never touch the file again.
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file));
        System.out.printf("Loaded %,d bytes into memory.%n%n", bytes.remaining());

        readOneInMemoryFile(bytes);
        readManyInMemoryFiles(bytes);
    }

    /// Open a single in-memory file with `InputFile.of(ByteBuffer)` and read its metadata and rows.
    /// The reader works exactly as it would over a file path — only the source of the bytes differs.
    private static void readOneInMemoryFile(ByteBuffer bytes) throws IOException {
        System.out.println("== InputFile.of(ByteBuffer) — one in-memory file ==");

        // Hand the reader its own view of the buffer so our copy's position stays put for reuse below.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(bytes.duplicate()))) {
            System.out.printf("  rows       : %,d%n", reader.getFileMetaData().numRows());
            System.out.printf("  row groups : %d%n", reader.getFileMetaData().rowGroups().size());

            FileSchema schema = reader.getFileSchema();
            int vendorIdx = schema.getColumn("VendorID").columnIndex();
            int distanceIdx = schema.getColumn("trip_distance").columnIndex();

            System.out.println("  first 3 trips:");
            try (RowReader rows = reader.rowReader()) {
                int printed = 0;
                while (rows.hasNext() && printed < 3) {
                    rows.next();
                    System.out.printf("    vendor=%d  distance=%.2f mi%n",
                            rows.getInt(vendorIdx), rows.getDouble(distanceIdx));
                    printed++;
                }
            }
        }
        System.out.println();
    }

    /// Open several in-memory blobs as one dataset with `InputFile.ofBuffers(...)` — one file per
    /// buffer, just like `ofPaths`. We pass the same bytes twice purely to show the API; in practice
    /// each buffer would be a different Parquet blob (e.g. several HTTP responses gathered in memory).
    private static void readManyInMemoryFiles(ByteBuffer bytes) throws IOException {
        System.out.println("== InputFile.ofBuffers(...) — several in-memory files as one dataset ==");

        List<InputFile> files = InputFile.ofBuffers(bytes.duplicate(), bytes.duplicate());
        try (ParquetFileReader reader = ParquetFileReader.openAll(files)) {
            System.out.printf("  multi-file  : %b%n", reader.isMultiFile());
            System.out.printf("  buffers     : %d%n", files.size());
            // Aggregate across the dataset: summing a column reads real values from both buffers,
            // so the total lands at twice a single file's mileage.
            System.out.printf("  total miles : %,.1f  (the same payload twice)%n", totalTripDistance(reader));
        }
    }

    /// Sum `trip_distance` over every row of the dataset — an aggregate that reads actual column
    /// values across all buffers, the in-memory counterpart of folding a column over a multi-file read.
    private static double totalTripDistance(ParquetFileReader reader) throws IOException {
        int distanceIdx = reader.getFileSchema().getColumn("trip_distance").columnIndex();
        double miles = 0;
        try (RowReader rowReader = reader.rowReader()) {
            while (rowReader.hasNext()) {
                rowReader.next();
                miles += rowReader.getDouble(distanceIdx);
            }
        }
        return miles;
    }
}
