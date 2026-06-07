/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.multifile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import dev.hardwood.Hardwood;
import dev.hardwood.HardwoodContext;
import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Read three months of taxi data as one logical dataset.
///
/// `Hardwood.openAll(...)` hands back a single [ParquetFileReader] over many files: the files share
/// one schema and one [RowReader] that walks every row of every file in a single loop. They also
/// share one thread pool, and the reader prefetches across the file boundary — while it decodes the
/// tail of one month it is already fetching the head of the next — so a multi-file scan stays on the
/// fast path instead of stalling at each seam.
///
/// The example does two things: a full scan over the combined reader to get the dataset's true row
/// count, and a wide all-column scan repeated across thread-pool sizes to show how [HardwoodContext]
/// concurrency translates into read throughput.
///
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
public final class Main {

    /// Counting rows needs to touch only one column; everything else stays off disk.
    private static final ColumnProjection ONE_COLUMN = ColumnProjection.columns("VendorID");

    /// The wide scan decodes every column — the work that the thread pool parallelizes.
    private static final ColumnProjection ALL_COLUMNS = ColumnProjection.all();

    /// Pool sizes to benchmark, doubling so the scaling is easy to read off.
    private static final int[] POOL_SIZES = {1, 2, 4, 8};

    /// Reported alongside the results so it is clear when a pool size oversubscribes the machine.
    private static final int CORES = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Exception {
        List<Path> months = Datasets.yellowTaxiMonths();

        // Time the reading work only — not the one-time data download above.
        long startNanos = System.nanoTime();

        try (Hardwood hardwood = Hardwood.create()) {
            oneLogicalDataset(hardwood, months);
            threadScaling(months);
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("%n(processed in: %,d ms)%n", elapsedMillis);
    }

    /// One reader over all three months: `openAll` presents them as a single logical dataset.
    ///
    /// A single `RowReader` walks every row of every file in one loop. A multi-file footer reports
    /// only the first file's count, so scanning is how we get the dataset's *true* total — the count
    /// here spans all three files, not just the first.
    private static void oneLogicalDataset(Hardwood hardwood, List<Path> months) throws IOException {
        try (ParquetFileReader reader = hardwood.openAll(InputFile.ofPaths(months))) {
            System.out.printf("== Combined with openAll (multi-file: %b) ==%n", reader.isMultiFile());
            System.out.printf("  files        : %,d%n", months.size());
            System.out.printf("  scanned rows : %,d%n", countRows(reader));
        }
    }

    /// Decode the whole dataset on pools of increasing size, and watch throughput climb.
    ///
    /// `HardwoodContext.create(n)` builds a pool of `n` threads; `openAll(..., context)` opens the
    /// dataset on it. The one pool is shared across every file — there is no per-file pool — so it is
    /// the single knob for read concurrency. Decoding all columns (the zstd decompression in
    /// particular) is CPU-heavy and splits across that pool, so a wider pool reads the same bytes
    /// faster, up to the point where memory bandwidth, not threads, becomes the limit.
    private static void threadScaling(List<Path> months) throws IOException {
        long totalBytes = 0;
        for (Path month : months) {
            totalBytes += Files.size(month);
        }

        System.out.printf("%n== Wide scan (all columns) throughput by pool size, %d cores ==%n", CORES);
        for (int threads : POOL_SIZES) {
            try (HardwoodContext context = HardwoodContext.create(threads);
                 ParquetFileReader reader = ParquetFileReader.openAll(InputFile.ofPaths(months), context)) {
                long scanNanos = System.nanoTime();
                wideScan(reader);
                double millis = (System.nanoTime() - scanNanos) / 1e6;
                double mbPerSecond = totalBytes / 1e6 / (millis / 1e3);
                System.out.printf("  %2d threads : %,8.1f ms  %,8.1f MB/s%n", threads, millis, mbPerSecond);
            }
        }
    }

    /// Reads every column of every file in batches — the columnar fast path. The heavy lifting is
    /// the per-chunk decode and decompression, which the reader runs on its thread pool; touching
    /// each column per batch forces that work so the timing reflects a real decode, not a no-op.
    private static long wideScan(ParquetFileReader reader) {
        long touched = 0;
        try (ColumnReaders columns = reader.columnReaders(ALL_COLUMNS)) {
            int columnCount = columns.getColumnCount();
            while (columns.nextBatch()) {
                for (int i = 0; i < columnCount; i++) {
                    touched += columns.getColumnReader(i).getRecordCount();
                }
            }
        }
        return touched;
    }

    /// Counts every row the reader exposes, decoding only the projected column. The loop just
    /// advances the reader — we never read a value — so the count is the scan's only output.
    private static long countRows(ParquetFileReader reader) {
        long count = 0;
        try (RowReader scan = reader.buildRowReader().projection(ONE_COLUMN).build()) {
            while (scan.hasNext()) {
                scan.next();
                count++;
            }
        }
        return count;
    }
}
