/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.hello;

import java.nio.file.Path;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.reader.Validity;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

/// Hello, Hardwood — open a Parquet file, inspect its schema, and read a few rows.
///
/// Follows the "See what's inside" and "Read rows" steps of the tutorial:
///   https://hardwood.dev/latest/tutorial/first-read/
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
public final class Main {

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        // Time the reading work only — not the one-time data download above.
        long startNanos = System.nanoTime();

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {

            // 1) Schema + row count — read from the footer, no row data is decoded.
            System.out.println("Total rows: " + reader.getFileMetaData().numRows());
            System.out.println("Row groups: " + reader.getFileMetaData().rowGroups().size());
            FileSchema schema = reader.getFileSchema();
            for (int i = 0; i < schema.getColumnCount(); i++) {
                ColumnSchema column = schema.getColumn(i);
                System.out.println("  " + column.name() + " : " + column.type());
            }

            // 2) Read the first few rows with typed accessors.
            //
            // Accessors come in a by-name form (getInt("VendorID")) and a by-index form
            // (getInt(vendorIdx)). Look the indices up once, before the loop: index-based
            // access skips the per-row name lookup, so it's faster in hot loops.
            int vendorIdx = schema.getColumn("VendorID").columnIndex();
            int distanceIdx = schema.getColumn("trip_distance").columnIndex();
            int passengerIdx = schema.getColumn("passenger_count").columnIndex();

            System.out.println("\nFirst 5 trips:");
            try (RowReader rows = reader.rowReader()) {
                int printed = 0;
                while (rows.hasNext() && printed < 5) {
                    rows.next();
                    // passenger_count is nullable — check isNull before the primitive accessor.
                    String passengers = rows.isNull(passengerIdx) ? "not specified" : Long.toString(rows.getLong(passengerIdx));
                    System.out.printf("vendor=%d  distance=%.2f mi  passengers=%s%n",
                            rows.getInt(vendorIdx),
                            rows.getDouble(distanceIdx),
                            passengers);
                    printed++;
                }
            }

            // 3) Sum a whole column the columnar way — read it in batches instead of
            //    row by row. Each batch hands you a primitive array plus a Validity that
            //    marks which slots are null, so you stay on the fast path and never box.
            try (ColumnReader fare = reader.columnReader("fare_amount")) {
                double total = 0;
                while (fare.nextBatch()) {
                    int count = fare.getRecordCount();
                    double[] values = fare.getDoubles();
                    Validity validity = fare.getLeafValidity();
                    boolean hasNulls = validity.hasNulls();

                    for (int i = 0; i < count; i++) {
                        if (!hasNulls || validity.isNotNull(i)) {
                            total += values[i];
                        }
                    }
                }
                System.out.printf("%nTotal fares: $%.2f%n", total);
            }
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("%n(processed in: %,d ms)%n", elapsedMillis);
    }
}
