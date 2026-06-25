/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.querycontrols;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowGroupPredicate;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnProjection;

/// Narrow a read with query controls — predicate pushdown, projection, a row limit, OFFSET
/// pagination, and byte-range splits — so the reader touches only the rows and columns it must.
///
/// Every control here is *pushed down* into the scan rather than applied afterwards in application
/// code over rows already read into memory:
/// the predicate is evaluated as pages decode (and whole row groups whose statistics can't match
/// are skipped), the projection keeps unselected columns off disk entirely, and `head(n)` stops
/// the scan once enough rows match. `skip(n)` then pages through the matches with an OFFSET, and a
/// byte range restricts the scan to the row groups in a slice of the file, which is how a query
/// engine hands disjoint work to parallel readers.
///
/// Follows the "Filter, Project, Limit, and Split" guide:
///   https://hardwood.dev/latest/how-to/query-controls/
/// API reference: https://hardwood.dev/api/1.0.0.Final/
public final class Main {

    /// The columns the first query reads back. Everything else in the file is never decoded.
    private static final String[] PROJECTION = {
        "tpep_pickup_datetime", "VendorID", "trip_distance", "fare_amount"
    };

    /// Page size and page count for the OFFSET/LIMIT pagination demo.
    private static final int PAGE_SIZE = 8;
    private static final int PAGES = 3;

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        // Time the queries only — not the one-time data download above.
        long startNanos = System.nanoTime();

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            long totalRows = reader.getFileMetaData().numRows();
            System.out.printf("Scanning %,d trips with pushed-down query controls.%n", totalRows);

            filterProjectLimit(reader);
            timestampWindow(reader);
            inLists(reader);
            threeValuedNullLogic(reader, totalRows);
            paginate(reader);
            byteRangeSplits(reader, file, totalRows);
        }

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        System.out.printf("%n(processed in: %,d ms)%n", elapsedMillis);
    }

    /// Predicate pushdown + projection + LIMIT, combined in one scan.
    ///
    /// `and(...)` joins three leaf predicates that exercise the numeric overloads (`gt` on a
    /// `double`, `ltEq` on a `double`, `eq` on an `int`) and `not(...)`. Only the four projected
    /// columns are decoded, and `head(5)` ends the scan as soon as five rows match.
    private static void filterProjectLimit(ParquetFileReader reader) {
        FilterPredicate longTrips = FilterPredicate.and(
                FilterPredicate.gt("trip_distance", 10.0),
                FilterPredicate.ltEq("fare_amount", 100.0),
                FilterPredicate.not(FilterPredicate.eq("VendorID", 2)));

        System.out.println("\n== Trips > 10 mi, fare <= $100, not vendor 2 — first 5 matches ==");
        try (RowReader rows = reader.buildRowReader()
                .projection(ColumnProjection.columns(PROJECTION))
                .filter(longTrips)
                .head(5)
                .build()) {
            while (rows.hasNext()) {
                rows.next();
                System.out.printf("  %s  vendor=%d  %5.2f mi  $%6.2f%n",
                        rows.getLocalTimestamp("tpep_pickup_datetime"),
                        rows.getInt("VendorID"),
                        rows.getDouble("trip_distance"),
                        rows.getDouble("fare_amount"));
            }
        }
    }

    /// A logical-type overload: filter a `TIMESTAMP` column with `Instant` bounds.
    ///
    /// `tpep_pickup_datetime` is a local-wall-clock timestamp (no zone), so its values are read
    /// back with `getLocalTimestamp`. The predicate still takes an `Instant` — Hardwood matches
    /// that instant's value against the stored timestamp — which is how you express a half-open
    /// time window `[Jan 15 00:00, Jan 16 00:00)` with `gtEq` and `lt`.
    private static void timestampWindow(ParquetFileReader reader) {
        FilterPredicate jan15 = FilterPredicate.and(
                FilterPredicate.gtEq("tpep_pickup_datetime", Instant.parse("2026-01-15T00:00:00Z")),
                FilterPredicate.lt("tpep_pickup_datetime", Instant.parse("2026-01-16T00:00:00Z")));

        System.out.println("\n== Pickups on Jan 15 — first 5 matches ==");
        try (RowReader rows = reader.buildRowReader()
                .projection(ColumnProjection.columns(PROJECTION))
                .filter(jan15)
                .head(5)
                .build()) {
            while (rows.hasNext()) {
                rows.next();
                System.out.printf("  %s  $%6.2f%n",
                        rows.getLocalTimestamp("tpep_pickup_datetime"),
                        rows.getDouble("fare_amount"));
            }
        }
    }

    /// Set membership: `in(...)` over the integer `VendorID` and `inStrings(...)` over the
    /// `store_and_fwd_flag` string column, joined with `and`. Counts the store-and-forwarded
    /// trips (those a vendor buffered offline before sending) for vendors 1 and 6.
    private static void inLists(ParquetFileReader reader) {
        FilterPredicate forwardedByVendor = FilterPredicate.and(
                FilterPredicate.in("VendorID", 1, 6),
                FilterPredicate.inStrings("store_and_fwd_flag", "Y"));

        long matches = count(reader, forwardedByVendor, "VendorID", "store_and_fwd_flag");
        System.out.printf("%n== Store-and-forwarded trips by vendor 1 or 6 ==%n  %,d trips%n", matches);
    }

    /// Three-valued logic: a comparison against `NULL` is `UNKNOWN`, never `true`, so null rows
    /// match neither `> 6` nor `<= 6` — only `isNull(...)` selects them. `passenger_count` is
    /// nullable here, so the three counts below make that concrete: the known rows plus the null
    /// rows account for every row in the file.
    private static void threeValuedNullLogic(ParquetFileReader reader, long totalRows) {
        long nulls = count(reader, FilterPredicate.isNull("passenger_count"), "passenger_count");
        long known = count(reader, FilterPredicate.isNotNull("passenger_count"), "passenger_count");
        long manyPassengers = count(reader, FilterPredicate.gt("passenger_count", 6L), "passenger_count");

        System.out.println("\n== passenger_count null handling ==");
        System.out.printf("  total rows        : %,d%n", totalRows);
        System.out.printf("  value present     : %,d%n", known);
        System.out.printf("  value is NULL     : %,d%n", nulls);
        System.out.printf("  value > 6         : %,d  (NULLs excluded)%n", manyPassengers);
        System.out.printf("  present + NULL     = %,d  (== total)%n", known + nulls);
    }

    /// Pagination: `skip(offset).head(limit)` is OFFSET/LIMIT over the *matching* stream.
    ///
    /// `head(limit)` is the page size and `skip(offset)` is a logical OFFSET that drops the first
    /// `offset` matching rows before the window starts. Because the matching stream has a stable
    /// order, walking `offset = 0, pageSize, 2*pageSize, ...` yields successive, non-overlapping
    /// pages — page 2 picks up exactly where page 1 left off. The running `#` index is the row's
    /// position in the whole result set (`offset + slot`), so the pages' alignment is visible.
    private static void paginate(ParquetFileReader reader) {
        FilterPredicate longTrips = FilterPredicate.gt("trip_distance", 20.0);

        System.out.printf("%n== Paginating trips > 20 mi, %d per page ==%n", PAGE_SIZE);
        for (int page = 0; page < PAGES; page++) {
            long offset = (long) page * PAGE_SIZE;
            System.out.printf("  -- page %d (skip %d, head %d) --%n", page + 1, offset, PAGE_SIZE);
            try (RowReader rows = reader.buildRowReader()
                    .projection(ColumnProjection.columns(PROJECTION))
                    .filter(longTrips)
                    .skip(offset)
                    .head(PAGE_SIZE)
                    .build()) {
                int slot = 0;
                while (rows.hasNext()) {
                    rows.next();
                    System.out.printf("    #%-3d %s  %6.2f mi  $%7.2f%n",
                            offset + slot + 1,
                            rows.getLocalTimestamp("tpep_pickup_datetime"),
                            rows.getDouble("trip_distance"),
                            rows.getDouble("fare_amount"));
                    slot++;
                }
                if (slot == 0) {
                    System.out.println("    (no more matching rows)");
                }
            }
        }
    }

    /// Byte-range splits: restrict a scan to the row groups whose start offset falls in a slice
    /// of the file. Splitting at the midpoint sends the earlier row groups to one reader and the
    /// rest to another; the two scans read disjoint row groups, so their row counts add up to the
    /// whole file without overlap — exactly how an engine parcels a file out to parallel workers.
    private static void byteRangeSplits(ParquetFileReader reader, Path file, long totalRows) throws IOException {
        long size = Files.size(file);
        long mid = size / 2;

        long firstHalf = count(reader, RowGroupPredicate.byteRange(0, mid), "VendorID");
        long secondHalf = count(reader, RowGroupPredicate.byteRange(mid, size), "VendorID");

        System.out.printf("%n== Byte-range splits (file is %,d bytes, split at %,d) ==%n", size, mid);
        List<RowGroup> rowGroups = reader.getFileMetaData().rowGroups();
        for (int i = 0; i < rowGroups.size(); i++) {
            long start = rowGroups.get(i).columns().get(0).chunkStartOffset();
            System.out.printf("  row group %d: starts at byte %,d -> %s half%n",
                    i, start, start < mid ? "first" : "second");
        }
        System.out.printf("  first half  : %,d rows%n", firstHalf);
        System.out.printf("  second half : %,d rows%n", secondHalf);
        System.out.printf("  combined     = %,d rows  (== total)%n", firstHalf + secondHalf);
    }

    /// Counts the rows matching a record predicate, decoding only the named columns. The loop
    /// just advances the reader; we never read a value, so the count is the scan's only output.
    private static long count(ParquetFileReader reader, FilterPredicate predicate, String... projected) {
        long matches = 0;
        try (RowReader rows = reader.buildRowReader()
                .projection(ColumnProjection.columns(projected))
                .filter(predicate)
                .build()) {
            while (rows.hasNext()) {
                rows.next();
                matches++;
            }
        }
        return matches;
    }

    /// Counts the rows a byte range selects, decoding only the named column.
    private static long count(ParquetFileReader reader, RowGroupPredicate range, String projected) {
        long matches = 0;
        try (RowReader rows = reader.buildRowReader()
                .projection(ColumnProjection.columns(projected))
                .filter(range)
                .build()) {
            while (rows.hasNext()) {
                rows.next();
                matches++;
            }
        }
        return matches;
    }
}
