/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.avro;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

import dev.hardwood.InputFile;
import dev.hardwood.avro.AvroReaders;
import dev.hardwood.avro.AvroRowReader;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnProjection;

/// Materialize Parquet rows as Avro [GenericRecord] — the shape Kafka and Spark consumers expect.
///
/// Hardwood derives an Avro schema from the Parquet footer and hands each row back as a
/// `GenericRecord`, so code already written against Avro can read Parquet unchanged. The same
/// projection, filter, and head/tail controls as the native reader are available on the Avro reader.
///
/// `AvroReaders`: https://hardwood.dev/api/1.0.0.CR2/dev/hardwood/avro/AvroReaders.html
/// How-to: https://hardwood.dev/latest/how-to/avro/
public final class Main {

    /// The columns this consumer reads. Projection narrows both the scan and the derived Avro
    /// schema to just these fields.
    private static final String[] PROJECTION = {
            "VendorID", "tpep_pickup_datetime", "passenger_count",
            "trip_distance", "store_and_fwd_flag", "total_amount"
    };

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        try (ParquetFileReader fileReader = ParquetFileReader.open(InputFile.of(file))) {
            printDerivedSchema(fileReader);
            consumeFirstTrips(fileReader);
            consumeLongTrips(fileReader);
            consumeLastTrips(fileReader);
        }
    }

    /// Show the Avro schema Hardwood derives from the Parquet footer for the projected columns.
    /// This is the contract a downstream Avro consumer codes against — built from the footer, no
    /// rows decoded. Each column is nullable in this file, so every field is an Avro union
    /// `["null", T]`; a required column would be a bare `T`.
    private static void printDerivedSchema(ParquetFileReader fileReader) {
        try (AvroRowReader reader = AvroReaders.buildRowReader(fileReader)
                .projection(ColumnProjection.columns(PROJECTION))
                .head(1)
                .build()) {
            Schema schema = reader.getSchema();
            System.out.println("== Derived Avro schema (" + schema.getFields().size() + " fields) ==");
            for (Schema.Field field : schema.getFields()) {
                System.out.printf("  %-22s %s%n", field.name(), field.schema());
            }
            System.out.println();
        }
    }

    /// Stream the first few rows as `GenericRecord` and read fields through the Avro API. Note the
    /// Java types Avro hands back — printed once by [#describeValueTypes].
    private static void consumeFirstTrips(ParquetFileReader fileReader) {
        System.out.println("== First 5 trips (as Avro GenericRecord) ==");
        try (AvroRowReader reader = AvroReaders.buildRowReader(fileReader)
                .projection(ColumnProjection.columns(PROJECTION))
                .head(5)
                .build()) {
            boolean described = false;
            while (reader.hasNext()) {
                GenericRecord record = reader.next();

                // Avro returns raw values: INT32 -> Integer, INT64 -> Long, DOUBLE -> Double, a
                // TIMESTAMP column -> Long (microseconds since the epoch). passenger_count is
                // nullable, so it is either a Long or null. Map them to your own types as needed.
                int vendor = (Integer) record.get("VendorID");
                long pickupMicros = (Long) record.get("tpep_pickup_datetime");
                Object passengers = record.get("passenger_count");
                double distance = (Double) record.get("trip_distance");
                double total = (Double) record.get("total_amount");

                System.out.printf("  %s  vendor=%d  %.2f mi  $%.2f  passengers=%s%n",
                        microsToTime(pickupMicros), vendor, distance, total,
                        passengers == null ? "n/a" : passengers);

                if (!described) {
                    describeValueTypes(record);
                    described = true;
                }
            }
        }
    }

    /// Apply a projection, a filter, and a row limit together on the Avro reader — the same query
    /// controls as the native row reader, just materializing `GenericRecord`. Here: the first 5
    /// long, pricey trips.
    private static void consumeLongTrips(ParquetFileReader fileReader) {
        FilterPredicate longTrip = FilterPredicate.and(
                FilterPredicate.gt("trip_distance", 20.0),
                FilterPredicate.gt("total_amount", 100.0));

        System.out.println("\n== First 5 trips over 20 mi and $100 (projection + filter + head) ==");
        try (AvroRowReader reader = AvroReaders.buildRowReader(fileReader)
                .projection(ColumnProjection.columns(PROJECTION))
                .filter(longTrip)
                .head(5)
                .build()) {
            while (reader.hasNext()) {
                GenericRecord record = reader.next();
                System.out.printf("  %s  %.2f mi  $%.2f%n",
                        microsToTime((Long) record.get("tpep_pickup_datetime")),
                        (Double) record.get("trip_distance"),
                        (Double) record.get("total_amount"));
            }
        }
    }

    /// Read the last few rows with `tail` — handy for "most recent records" consumers. `tail` is
    /// single-file only and cannot combine with `head` or `filter`.
    private static void consumeLastTrips(ParquetFileReader fileReader) {
        System.out.println("\n== Last 3 trips in the file (projection + tail) ==");
        try (AvroRowReader reader = AvroReaders.buildRowReader(fileReader)
                .projection(ColumnProjection.columns(PROJECTION))
                .tail(3)
                .build()) {
            while (reader.hasNext()) {
                GenericRecord record = reader.next();
                System.out.printf("  %s  %.2f mi  $%.2f%n",
                        microsToTime((Long) record.get("tpep_pickup_datetime")),
                        (Double) record.get("trip_distance"),
                        (Double) record.get("total_amount"));
            }
        }
    }

    /// Print the Java class behind each field's value — the representations a consumer must map.
    /// Hardwood materializes raw values: a STRING column is a `java.lang.String`, and a TIMESTAMP
    /// is a `Long` of microseconds (not a `java.time` type), so render those yourself.
    private static void describeValueTypes(GenericRecord record) {
        System.out.println("\n  Value representations for this record:");
        for (String field : PROJECTION) {
            Object value = record.get(field);
            String type = value == null ? "null (column was null)" : value.getClass().getName();
            System.out.printf("    %-22s -> %s%n", field, type);
        }
    }

    /// A TIMESTAMP column arrives as raw microseconds since the epoch; the taxi timestamps are
    /// local wall-clock time, so render them as a `LocalDateTime` for display.
    private static LocalDateTime microsToTime(long epochMicros) {
        long seconds = Math.floorDiv(epochMicros, 1_000_000L);
        long nanos = Math.floorMod(epochMicros, 1_000_000L) * 1_000L;
        return LocalDateTime.ofEpochSecond(seconds, (int) nanos, ZoneOffset.UTC);
    }
}
