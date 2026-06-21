/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.concurrentcolumnconsumer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnProjection;

/// Scale *consumption*, not just decoding — fan a batch's per-value work across a thread pool.
///
/// The columnar reader's payoff is not only that it skips the row cursor's per-value overhead; it
/// also hands back each batch as a plain primitive array the caller fully owns. Once `nextBatch()`
/// returns that `double[]`, nothing in the reader still points at it, so it is free to cross a
/// thread boundary. This example projects two columns, and for every batch splits the returned
/// array into contiguous ranges processed in parallel by an `ExecutorService`, then reduces the
/// per-range partials back into one result.
///
/// The per-value computation here is deliberately heavy — a stand-in for a real pricing model or
/// feature transform — because that is the honest precondition: **consumer-side threading only
/// pays off when the per-value work is the bottleneck.** For a light fold (a plain `sum +=`) the
/// job is memory-bandwidth-bound and threading the consumer buys nothing. To make the win visible
/// the example runs the same work twice per batch — once single-threaded, once across the pool —
/// and prints both timings. The two paths use the identical range decomposition, so they return a
/// bit-for-bit identical result; only the wall-clock differs.
///
/// This is the parallel counterpart to the `column-analytics` example, which covers the
/// single-threaded columnar path.
///
/// Follow the "Column-Oriented Reading" guide:
///   https://hardwood.dev/latest/how-to/column-reader/
/// Concept — RowReader vs. ColumnReader:
///   https://hardwood.dev/latest/concepts/reader-models/
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
public final class Main {

    /// The two columns we score. Everything else in the file is never read off disk.
    private static final String[] COLUMNS = {"fare_amount", "trip_distance"};

    /// How many ranges each batch is split into, and the size of the worker pool. One range per
    /// core lets every core stay busy without oversubscribing.
    private static final int WORKERS = Runtime.getRuntime().availableProcessors();

    /// Iterations of the per-value model. Higher means heavier per value — and a bigger parallel
    /// win. Drop it toward zero and the consumer goes bandwidth-bound, where threading stops paying.
    private static final int ITERATIONS = 60;

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        // One pool for the whole run, reused across every batch — never one pool per batch.
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);

        long rows = 0;
        long sequentialNanos = 0;
        long parallelNanos = 0;
        double sequentialTotal = 0;
        double parallelTotal = 0;

        // Project exactly the two columns we score; the other ~17 columns are never fetched.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
                ColumnReaders columns = reader.columnReaders(ColumnProjection.columns(COLUMNS))) {

            ColumnReader fareCol = columns.getColumnReader("fare_amount");
            ColumnReader distanceCol = columns.getColumnReader("trip_distance");

            // The driving thread owns the cursor: only this loop calls nextBatch(). The arrays it
            // hands back are what we pass to the workers — the reader never touches them again.
            while (columns.nextBatch()) {
                int count = columns.getRecordCount();
                double[] fares = fareCol.getDoubles();
                double[] distances = distanceCol.getDoubles();

                // Same work, two ways. We time each consume step on its own; the one-time decode
                // above is excluded from both so the timings isolate the threading effect.
                long startSeq = System.nanoTime();
                sequentialTotal += consumeSequential(fares, distances, count);
                sequentialNanos += System.nanoTime() - startSeq;

                long startPar = System.nanoTime();
                parallelTotal += consumeParallel(fares, distances, count, pool);
                parallelNanos += System.nanoTime() - startPar;

                rows += count;
            }
        } finally {
            pool.shutdown();
        }

        // The two paths decompose each batch into the identical ranges and reduce the partials in
        // the same order, so floating-point summation gives a bit-for-bit identical result. A
        // mismatch would mean the hand-off corrupted a batch — fail loudly rather than trust it.
        if (sequentialTotal != parallelTotal) {
            throw new IllegalStateException(
                    "Parallel result diverged from the single-threaded baseline: "
                            + parallelTotal + " vs " + sequentialTotal);
        }

        System.out.printf("Scored %,d trips with a heavy per-value model (%d iterations each).%n%n",
                rows, ITERATIONS);
        System.out.printf("Worker threads (cores)  : %d%n", WORKERS);
        System.out.printf("Model score (checksum)  : %.6e%n", parallelTotal);
        System.out.printf("Single-threaded consume : %,d ms%n", sequentialNanos / 1_000_000);
        System.out.printf("Parallel consume        : %,d ms%n", parallelNanos / 1_000_000);
        System.out.printf("Speedup                 : %.2fx%n", (double) sequentialNanos / parallelNanos);
    }

    /// Consume the whole batch on the calling thread, range by range, summing the partials in
    /// range order — the single-threaded baseline.
    private static double consumeSequential(double[] fares, double[] distances, int count) {
        double total = 0;
        for (int r = 0; r < WORKERS; r++) {
            total += scoreRange(fares, distances, rangeBound(count, r), rangeBound(count, r + 1));
        }
        return total;
    }

    /// Consume the same batch by handing each range to the pool, then reducing the partials in the
    /// same range order so the result matches the baseline exactly.
    private static double consumeParallel(double[] fares, double[] distances, int count, ExecutorService pool)
            throws InterruptedException, ExecutionException {
        List<Future<Double>> partials = new ArrayList<>(WORKERS);
        for (int r = 0; r < WORKERS; r++) {
            int start = rangeBound(count, r);
            int end = rangeBound(count, r + 1);
            // Only the primitive arrays and two ints cross the boundary; the reader stays put.
            partials.add(pool.submit(() -> scoreRange(fares, distances, start, end)));
        }
        double total = 0;
        for (Future<Double> partial : partials) {
            total += partial.get();
        }
        return total;
    }

    /// Boundary `r` of the `WORKERS` even splits of `[0, count)` — the start of range `r` and the
    /// end of range `r - 1`. Scaling the boundary spreads the remainder evenly and yields empty
    /// ranges when `count < WORKERS`.
    private static int rangeBound(int count, int r) {
        return Math.toIntExact((long) count * r / WORKERS);
    }

    /// Sum the per-value model over `[start, end)`. This is the deliberately expensive consumer —
    /// the work that makes splitting the batch across threads worthwhile.
    private static double scoreRange(double[] fares, double[] distances, int start, int end) {
        double total = 0;
        for (int i = start; i < end; i++) {
            total += score(fares[i], distances[i]);
        }
        return total;
    }

    /// A stand-in for a genuinely expensive per-row computation — a pricing model, an ML feature
    /// transform, a similarity score. A perturbed Newton iteration toward `sqrt(distance)`: cheap
    /// to read, but the transcendental term keeps the JIT from folding the loop away, so the cost
    /// is real and per value. Nulls decode to 0.0 and feed in uniformly; the result is a synthetic
    /// checksum, not a money figure, so no null guard is needed (see `column-analytics` for the
    /// null-aware path).
    private static double score(double fare, double distance) {
        double estimate = Math.abs(fare) + 1.0;
        double target = Math.abs(distance) + 1.0;
        for (int k = 0; k < ITERATIONS; k++) {
            estimate = 0.5 * (estimate + target / estimate);
            estimate += Math.sin(estimate) * Math.cos(target) * 1e-3;
        }
        return estimate;
    }
}
