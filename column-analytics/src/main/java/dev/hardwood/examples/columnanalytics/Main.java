/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.columnanalytics;

import java.nio.file.Path;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ColumnReaders;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.Validity;
import dev.hardwood.schema.ColumnProjection;

/// Column-oriented analytics — aggregate a whole month of taxi trips the fast way.
///
/// Where the hello-hardwood example reads one row at a time, this one reads a handful of
/// columns in batches of primitive arrays and never touches the rows it doesn't need. That is
/// the columnar path: no per-row method calls, no boxing, and any column you don't project is
/// never fetched or decoded.
///
/// Follows the "Column-Oriented Reading" guide:
///   https://hardwood.dev/latest/how-to/column-reader/
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
///
/// Contrast with the row API: the equivalent `RowReader` loop would call `getLong`/`getDouble`
/// once per column per row (~3.7M rows x 5 columns ≈ 18M virtual calls). Here each `nextBatch()`
/// hands back the whole batch as a primitive array, so the inner loop is plain array arithmetic.
public final class Main {

    /// The columns we aggregate, used to drive the projection.
    private static final String[] COLUMNS = {
        "passenger_count", "trip_distance", "fare_amount", "tip_amount", "total_amount"
    };

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        // Time the read + aggregation only — not the one-time data download above.
        long startNanos = System.nanoTime();

        // Project exactly the five columns we aggregate. The other ~15 columns in the file are
        // never read off disk.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file));
                ColumnReaders columns = reader.columnReaders(ColumnProjection.columns(COLUMNS))) {

            // Look the per-column readers up once — getColumnReader does a name lookup — and reuse
            // them across every batch.
            ColumnReader passengerCol = columns.getColumnReader("passenger_count");
            ColumnReader distanceCol = columns.getColumnReader("trip_distance");
            ColumnReader fareCol = columns.getColumnReader("fare_amount");
            ColumnReader tipCol = columns.getColumnReader("tip_amount");
            ColumnReader totalCol = columns.getColumnReader("total_amount");

            long trips = 0;
            long passengerSum = 0;
            long passengerKnown = 0;
            double distanceSum = 0;
            long distanceKnown = 0;
            double fareSum = 0;
            double tipSum = 0;
            double totalSum = 0;
            long totalKnown = 0;

            // ColumnReaders.nextBatch() advances every reader in lockstep and returns false once
            // any column is exhausted, so all five arrays below describe the same batch of records.
            while (columns.nextBatch()) {
                int count = columns.getRecordCount();

                long[] passengers = passengerCol.getLongs();
                double[] distances = distanceCol.getDoubles();
                double[] fares = fareCol.getDoubles();
                double[] tips = tipCol.getDoubles();
                double[] totals = totalCol.getDoubles();

                // Hoist each column's null check out of the inner loop. hasNulls() is an O(1) per-batch
                // test, so a false result drops straight to the fast path with no per-element call.
                // This form suits null-sparse columns; for null-dense ones, Validity.words() skips
                // straight to the present positions — see the "null-check loop shapes" note in the guide.
                Validity passengerValidity = passengerCol.getLeafValidity();
                Validity distanceValidity = distanceCol.getLeafValidity();
                Validity fareValidity = fareCol.getLeafValidity();
                Validity tipValidity = tipCol.getLeafValidity();
                Validity totalValidity = totalCol.getLeafValidity();
                boolean passengerHasNulls = passengerValidity.hasNulls();
                boolean distanceHasNulls = distanceValidity.hasNulls();
                boolean fareHasNulls = fareValidity.hasNulls();
                boolean tipHasNulls = tipValidity.hasNulls();
                boolean totalHasNulls = totalValidity.hasNulls();

                trips += count;
                for (int i = 0; i < count; i++) {
                    // passenger_count carries nulls here (~29% of trips), and a null reads back as 0.
                    // The guard keeps it out of both passengerSum and passengerKnown — without it the
                    // average would divide by an inflated count. The money columns are non-null in this
                    // file, so their guard is free (hasNulls() is false).
                    if (!passengerHasNulls || passengerValidity.isNotNull(i)) {
                        passengerSum += passengers[i];
                        passengerKnown++;
                    }
                    if (!distanceHasNulls || distanceValidity.isNotNull(i)) {
                        distanceSum += distances[i];
                        distanceKnown++;
                    }
                    if (!fareHasNulls || fareValidity.isNotNull(i)) {
                        fareSum += fares[i];
                    }
                    if (!tipHasNulls || tipValidity.isNotNull(i)) {
                        tipSum += tips[i];
                    }
                    if (!totalHasNulls || totalValidity.isNotNull(i)) {
                        totalSum += totals[i];
                        totalKnown++;
                    }
                }
            }

            System.out.printf("Analyzed %,d trips across the month.%n%n", trips);
            System.out.printf("Avg passengers per trip : %.2f%n", (double) passengerSum / passengerKnown);
            System.out.printf("Avg trip distance       : %.2f mi%n", distanceSum / distanceKnown);
            System.out.printf("Total fares             : $%,.2f%n", fareSum);
            System.out.printf("Total tips              : $%,.2f%n", tipSum);
            System.out.printf("Avg tip as %% of fare    : %.1f%%%n", tipSum / fareSum * 100);
            System.out.printf("Avg total per trip      : $%.2f%n", totalSum / totalKnown);
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("%n(processed in: %,d ms)%n", elapsedMillis);
    }
}
