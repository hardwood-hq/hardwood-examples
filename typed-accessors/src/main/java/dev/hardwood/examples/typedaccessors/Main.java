/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.typedaccessors;

import java.io.IOException;
import java.util.HexFormat;

import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqInterval;
import dev.hardwood.schema.ColumnSchema;

/// Reading Parquet's logical types with the **typed Row API accessors**.
///
/// Parquet stores a handful of physical types (INT32, INT64, BYTE_ARRAY, …) and layers *logical
/// types* on top to give them meaning: an INT32 tagged `DATE`, a BYTE_ARRAY tagged `JSON`, a
/// fixed-length 12 bytes tagged `INTERVAL`. Hardwood's accessors decode each to the natural Java
/// type — [java.time.LocalDate], [java.time.Instant], [java.math.BigDecimal], [java.util.UUID] —
/// so you never hand-parse the bytes. The accessors are *strict*: ask a column for the wrong type
/// and it throws rather than guessing, which is what keeps a read honest.
///
/// Each section below isolates one corner the everyday primitive accessors can't reach. See:
///   - Typed accessors:   https://hardwood.dev/latest/reference/accessors/
///   - Timestamp concept: https://hardwood.dev/latest/concepts/timestamps/
public final class Main {

    public static void main(String[] args) throws Exception {
        temporalAndNumericTypes();
        jsonAndBson();
        utcVersusLocalTimestamps();
        intervals();
        float16();
        nullType();
    }

    /// DATE, TIME, TIMESTAMP, DECIMAL, and UUID — each decoded to its natural Java type. The TIME
    /// and TIMESTAMP columns come in millis/micros/nanos variants to show sub-second precision is
    /// preserved end to end.
    private static void temporalAndNumericTypes() throws IOException {
        System.out.println("=== Temporal & numeric types (first row) ===");
        try (ParquetFileReader reader = ParquetFileReader.open(Datasets.sample(Datasets.LOGICAL_TYPES));
             RowReader row = reader.rowReader()) {
            row.next();
            System.out.println("name           : " + row.getString("name"));
            System.out.println("birth_date     : " + row.getDate("birth_date"));         // INT32  -> LocalDate
            System.out.println("created millis : " + row.getTimestamp("created_at_millis")); // INT64 -> Instant
            System.out.println("created micros : " + row.getTimestamp("created_at_micros"));
            System.out.println("created nanos  : " + row.getTimestamp("created_at_nanos"));
            System.out.println("wake millis    : " + row.getTime("wake_time_millis"));   // INT32 -> LocalTime
            System.out.println("wake micros    : " + row.getTime("wake_time_micros"));
            System.out.println("wake nanos     : " + row.getTime("wake_time_nanos"));
            System.out.println("balance        : " + row.getDecimal("balance"));         // FIXED_LEN_BYTE_ARRAY -> BigDecimal
            System.out.println("account_id     : " + row.getUuid("account_id"));         // 16 bytes -> UUID
        }
    }

    /// JSON and BSON both ride on a BYTE_ARRAY physical type; only the logical-type annotation tells
    /// them apart. `getString` hands back the raw UTF-8 JSON text and `getBinary` the raw BSON bytes,
    /// unchanged — the schema's [ColumnSchema#logicalType()] is how you recognize which is which.
    private static void jsonAndBson() throws IOException {
        System.out.println("\n=== JSON & BSON (BYTE_ARRAY + logical annotation) ===");
        try (ParquetFileReader reader = ParquetFileReader.open(Datasets.sample(Datasets.LOGICAL_TYPES))) {
            ColumnSchema json = reader.getFileSchema().getColumn("profile_json");
            ColumnSchema bson = reader.getFileSchema().getColumn("bson_payload");
            System.out.printf("profile_json : %s annotated %s%n", json.type(), logicalTypeName(json));
            System.out.printf("bson_payload : %s annotated %s%n", bson.type(), logicalTypeName(bson));
            try (RowReader row = reader.rowReader()) {
                while (row.hasNext()) {
                    row.next();
                    String text = row.getString("profile_json");
                    byte[] raw = row.getBinary("bson_payload");
                    System.out.printf("  json=%-34s bson=0x%s%n", text, HexFormat.of().formatHex(raw));
                }
            }
        }
    }

    /// Two timestamp columns that differ only in their `isAdjustedToUTC` flag. A UTC column is a
    /// point on the global timeline, read with `getTimestamp` as an [java.time.Instant]; a local
    /// column is a zone-free wall-clock reading, read with `getLocalTimestamp` as a
    /// [java.time.LocalDateTime]. The accessors refuse to cross over: each throws on the other kind,
    /// so a zone-less reading can never be silently mistaken for an instant.
    private static void utcVersusLocalTimestamps() throws IOException {
        System.out.println("\n=== UTC vs. local timestamps (first row) ===");
        try (ParquetFileReader reader = ParquetFileReader.open(Datasets.sample(Datasets.LOCAL_TIMESTAMP));
             RowReader row = reader.rowReader()) {
            row.next();
            System.out.println("utc_micros   (getTimestamp)      : " + row.getTimestamp("utc_micros"));
            System.out.println("local_micros (getLocalTimestamp) : " + row.getLocalTimestamp("local_micros"));

            // Strict typing in action: the wrong accessor fails fast instead of guessing a zone.
            try {
                row.getLocalTimestamp("utc_micros");
            } catch (IllegalStateException e) {
                System.out.println("getLocalTimestamp(\"utc_micros\") rejected: " + e.getMessage());
            }
        }
    }

    /// INTERVAL is a fixed 12 bytes carrying independent `months`, `days`, and `milliseconds`
    /// fields, decoded into the [PqInterval] record. The fields are kept separate on purpose —
    /// 30 days is not folded into a month — because calendar lengths vary. The third row is null.
    private static void intervals() throws IOException {
        System.out.println("\n=== INTERVAL (months / days / milliseconds) ===");
        try (ParquetFileReader reader = ParquetFileReader.open(Datasets.sample(Datasets.INTERVAL));
             RowReader row = reader.rowReader()) {
            while (row.hasNext()) {
                row.next();
                if (row.isNull("duration")) {
                    System.out.println("  duration: (null)");
                    continue;
                }
                PqInterval d = row.getInterval("duration");
                System.out.printf("  duration: %d months, %d days, %d ms%n", d.months(), d.days(), d.milliseconds());
            }
        }
    }

    /// FLOAT16 (IEEE half precision) is stored as a 2-byte fixed-length value; `getFloat` widens it
    /// to a Java `float`, so the special values — the max finite 65504, infinity, and NaN — all
    /// survive the round trip. The last row is null.
    private static void float16() throws IOException {
        System.out.println("\n=== FLOAT16 widened to float ===");
        try (ParquetFileReader reader = ParquetFileReader.open(Datasets.sample(Datasets.FLOAT16));
             RowReader row = reader.rowReader()) {
            while (row.hasNext()) {
                row.next();
                System.out.println("  half: " + (row.isNull("half") ? "(null)" : Float.toString(row.getFloat("half"))));
            }
        }
    }

    /// The NULL logical type is a column with no values at all — every row is absent by definition.
    /// You recognize it from the schema and never call a value accessor; `isNull` is always true.
    private static void nullType() throws IOException {
        System.out.println("\n=== NULL type (every value absent) ===");
        try (ParquetFileReader reader = ParquetFileReader.open(Datasets.sample(Datasets.NULL_TYPE))) {
            ColumnSchema nothing = reader.getFileSchema().getColumn("nothing");
            System.out.println("nothing : annotated " + logicalTypeName(nothing));
            int rows = 0;
            int nulls = 0;
            try (RowReader row = reader.rowReader()) {
                while (row.hasNext()) {
                    row.next();
                    rows++;
                    if (row.isNull("nothing")) {
                        nulls++;
                    }
                }
            }
            System.out.printf("  %d rows, %d null%n", rows, nulls);
        }
    }

    /// Names the column's logical-type annotation (e.g. `JsonType`, `Float16Type`), or `none` when
    /// the column carries only a physical type. The annotation is a sealed type, so real code can
    /// also `switch` over it — here we just need its label.
    private static String logicalTypeName(ColumnSchema column) {
        return column.logicalType() == null ? "none" : column.logicalType().getClass().getSimpleName();
    }
}
