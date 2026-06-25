/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.compat;

import static org.apache.parquet.filter2.predicate.FilterApi.and;
import static org.apache.parquet.filter2.predicate.FilterApi.doubleColumn;
import static org.apache.parquet.filter2.predicate.FilterApi.gt;

import org.apache.hadoop.fs.Path; // Hadoop shim — no Hadoop dependency on the classpath.
import org.apache.parquet.example.data.Group;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.filter2.predicate.FilterPredicate;
import org.apache.parquet.hadoop.GroupReadSupport;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.schema.GroupType;

/// Read a Parquet file through Apache parquet-java's `ParquetReader<Group>` API — except every
/// `org.apache.parquet.*` (and Hadoop `Path`) type here is a shim from `hardwood-parquet-java-compat`,
/// so the read is backed by Hardwood with no code changes from the parquet-java original.
///
/// Experimental: the compat module's API surface and behavior may change without prior deprecation.
/// It is mutually exclusive with the real parquet-java — both own the `org.apache.parquet.*` package
/// names, so only one can be on the classpath. This example depends on the compat module alone.
///
/// How-to: https://hardwood.dev/latest/how-to/compat/
public final class Main {

    public static void main(String[] args) throws Exception {
        // Datasets returns a java.nio.file.Path; the parquet-java API wants a Hadoop Path, so wrap
        // the location string. This is the Hadoop Path shim, not the real org.apache.hadoop one.
        java.nio.file.Path file = Datasets.yellowTaxi();
        Path path = new Path(file.toString());

        readFirstTrips(path);
        readLongTrips(path);
    }

    /// Open the file with `ParquetReader.builder(new GroupReadSupport(), path)` and pull rows as
    /// `Group` — the canonical parquet-java read loop, unchanged.
    private static void readFirstTrips(Path path) throws Exception {
        System.out.println("== First 5 trips (parquet-java ParquetReader<Group>, backed by Hardwood) ==");
        try (ParquetReader<Group> reader = ParquetReader.builder(new GroupReadSupport(), path).build()) {
            Group record;
            int printed = 0;
            boolean schemaPrinted = false;
            while ((record = reader.read()) != null && printed < 5) {
                if (!schemaPrinted) {
                    printSchema(record.getType());
                    schemaPrinted = true;
                }

                // Read fields the parquet-java way: getX(field, index). Each is the first (index 0)
                // value of the field on this row.
                int vendor = record.getInteger("VendorID", 0);
                double distance = record.getDouble("trip_distance", 0);
                double total = record.getDouble("total_amount", 0);

                // passenger_count is nullable. parquet-java reports a null field as zero
                // repetitions, so guard the accessor with getFieldRepetitionCount.
                String passengers = record.getFieldRepetitionCount("passenger_count") == 0
                        ? "not specified"
                        : Long.toString(record.getLong("passenger_count", 0));

                System.out.printf("  vendor=%d  distance=%.2f mi  $%.2f  passengers=%s%n",
                        vendor, distance, total, passengers);
                printed++;
            }
        }
    }

    /// Push a predicate down with the standard `FilterApi` / `FilterCompat` classes: only rows from
    /// row groups whose statistics can match are decoded. Same filter API as parquet-java.
    private static void readLongTrips(Path path) throws Exception {
        FilterPredicate longTrip = and(
                gt(doubleColumn("trip_distance"), 20.0),
                gt(doubleColumn("total_amount"), 100.0));

        System.out.println("\n== First 5 trips over 20 mi and $100 (FilterApi pushdown) ==");
        try (ParquetReader<Group> reader = ParquetReader.builder(new GroupReadSupport(), path)
                .withFilter(FilterCompat.get(longTrip))
                .build()) {
            Group record;
            int printed = 0;
            while ((record = reader.read()) != null && printed < 5) {
                System.out.printf("  %.2f mi  $%.2f%n",
                        record.getDouble("trip_distance", 0),
                        record.getDouble("total_amount", 0));
                printed++;
            }
        }
    }

    /// Print the file's column names and types from the `GroupType` schema attached to a row — the
    /// parquet-java `MessageType` / `GroupType` view of the footer.
    private static void printSchema(GroupType schema) {
        System.out.println("  schema: " + schema.getFieldCount() + " columns");
        for (int i = 0; i < schema.getFieldCount(); i++) {
            System.out.println("    " + schema.getType(i).getName());
        }
        System.out.println();
    }
}
