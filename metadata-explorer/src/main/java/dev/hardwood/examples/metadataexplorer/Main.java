/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.metadataexplorer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.ColumnChunk;
import dev.hardwood.metadata.ColumnMetaData;
import dev.hardwood.metadata.FileMetaData;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.metadata.Statistics;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

/// Metadata Explorer — describe a Parquet file from its footer alone, decoding no row data.
///
/// A Parquet file ends with a footer: a compact index of the schema, the row groups, and,
/// for each column chunk, where its pages live and what they contain (codec, sizes, null
/// count, min/max statistics). Tools like `DuckDB`, `Spark`, and query
/// planners read this footer first to decide what to skip — often answering a query without
/// touching a single page of values. This example prints that footer so you can see what a
/// reader sees before any decoding happens.
///
/// Follow the "Inspect File Metadata" guide:
///   https://hardwood.dev/latest/how-to/metadata/
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
public final class Main {

    public static void main(String[] args) throws Exception {
        Path file = Datasets.yellowTaxi();

        // Opening a reader parses only the footer — no column pages are read here.
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(file))) {
            FileMetaData meta = reader.getFileMetaData();
            FileSchema schema = reader.getFileSchema();

            printFileSummary(meta);
            printSchema(schema);
            printRowGroups(meta);
        }
    }

    /// File-level facts that live in the footer header: format version, total rows, the
    /// writer's identity, and any key-value metadata it embedded (e.g. an Arrow or pandas schema).
    private static void printFileSummary(FileMetaData meta) {
        System.out.println("== File ==");
        System.out.println("Format version : " + meta.version());
        System.out.printf("Total rows     : %,d%n", meta.numRows());
        System.out.println("Row groups     : " + meta.rowGroups().size());
        System.out.println("Created by     : " + meta.createdBy());

        Map<String, String> keyValue = meta.keyValueMetadata();
        System.out.println("Key-value metadata : " + (keyValue.isEmpty() ? "(none)" : keyValue.size() + " entr" + (keyValue.size() == 1 ? "y" : "ies")));
        for (Map.Entry<String, String> entry : keyValue.entrySet()) {
            // These values can be large (a whole serialized schema), so show only a preview.
            System.out.println("  " + entry.getKey() + " = " + preview(entry.getValue(), 80));
        }
    }

    /// The schema, leaf column by leaf column. Each column carries a *physical* type (how the
    /// bytes are stored — `INT32`, `DOUBLE`, `BYTE_ARRAY`, …) and, optionally, a *logical* type
    /// that gives those bytes meaning (a `BYTE_ARRAY` that is really a `String`, an `INT64` that
    /// is really a UTC `Timestamp`). Readers map both to decide how to hand values back.
    private static void printSchema(FileSchema schema) {
        System.out.println("\n== Schema ==");
        for (ColumnSchema column : schema.getColumns()) {
            String logical = column.logicalType() == null ? "" : " as " + column.logicalType();
            System.out.printf("  %-26s %s%s  [%s]%n",
                    column.fieldPath(),
                    column.type(),
                    logical,
                    column.repetitionType());
        }
    }

    /// The body of the footer: one entry per row group, each holding one column chunk per leaf
    /// column. The chunk metadata is what lets a reader prune work — sizes tell it how much I/O a
    /// column costs, and statistics let it skip a whole chunk whose `[min, max]` can't match a
    /// predicate.
    private static void printRowGroups(FileMetaData meta) {
        List<RowGroup> rowGroups = meta.rowGroups();
        for (int g = 0; g < rowGroups.size(); g++) {
            RowGroup group = rowGroups.get(g);
            System.out.printf("%n== Row group %d of %d ==%n", g + 1, rowGroups.size());
            System.out.printf("Rows: %,d   Size: %,d B   Columns: %d%n",
                    group.numRows(), group.totalByteSize(), group.columns().size());

            for (ColumnChunk chunk : group.columns()) {
                printColumnChunk(chunk.metaData());
            }
        }
    }

    /// One column chunk: its codec and on-disk footprint, how many values it holds and how many
    /// are null, and the decoded min/max.
    private static void printColumnChunk(ColumnMetaData column) {
        long compressed = column.totalCompressedSize();
        long uncompressed = column.totalUncompressedSize();
        // Guard the ratio against a zero-byte chunk so an empty column can't divide by zero.
        String ratio = compressed == 0 ? "n/a" : String.format("%.1fx", (double) uncompressed / compressed);

        System.out.printf("%n  %s  (%s)%n", column.pathInSchema(), column.type());
        System.out.printf("    codec=%s  %,d B -> %,d B (%s)  encodings=%s%n",
                column.codec(),
                uncompressed,
                compressed,
                ratio,
                column.encodings());

        Statistics stats = column.statistics();
        if (stats == null) {
            System.out.printf("    values=%,d  (no statistics)%n", column.numValues());
        } else {
            String nulls = stats.nullCount() == null ? "unknown" : String.format("%,d", stats.nullCount());
            System.out.printf("    values=%,d  nulls=%s  min=%s  max=%s%n",
                    column.numValues(),
                    nulls,
                    decode(column.type(), stats.minValue()),
                    decode(column.type(), stats.maxValue()));
        }
    }

    /// Decodes a raw statistic (min or max) into a readable value using the column's physical
    /// type. Statistics are stored in Parquet's PLAIN encoding — fixed-width little-endian for
    /// the numeric types, raw bytes for `BYTE_ARRAY` — so the same bytes mean different things
    /// depending on the type, which is why decoding needs it.
    private static String decode(PhysicalType type, byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "(absent)";
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        return switch (type) {
            case BOOLEAN -> Boolean.toString(raw[0] != 0);
            case INT32 -> Integer.toString(buffer.getInt());
            case INT64 -> Long.toString(buffer.getLong());
            case FLOAT -> Float.toString(buffer.getFloat());
            case DOUBLE -> Double.toString(buffer.getDouble());
            case BYTE_ARRAY, FIXED_LEN_BYTE_ARRAY -> text(raw);
            // INT96 is a deprecated timestamp layout; show raw bytes rather than guess at it.
            case INT96 -> hex(raw);
        };
    }

    /// Renders bytes as a quoted UTF-8 string when they are a printable text, else as hex. Min/max
    /// for a string column is text; for a decimal or other binary it is opaque bytes.
    private static String text(byte[] raw) {
        for (byte b : raw) {
            if (b < 0x20 || b == 0x7f) {
                return hex(raw);
            }
        }
        return '"' + preview(new String(raw, StandardCharsets.UTF_8), 32) + '"';
    }

    private static String hex(byte[] raw) {
        int shown = Math.min(raw.length, 12);
        StringBuilder sb = new StringBuilder("0x");
        for (int i = 0; i < shown; i++) {
            sb.append(String.format("%02x", raw[i]));
        }
        if (shown < raw.length) {
            sb.append("… (").append(raw.length).append(" bytes)");
        }
        return sb.toString();
    }

    /// Truncates a long value to keep one line of output to one line.
    private static String preview(String value, int max) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
