/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.variantcolumns;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqVariant;
import dev.hardwood.row.PqVariantArray;
import dev.hardwood.row.PqVariantObject;

/// Read semi-structured, JSON-like VARIANT columns — a tour of every Variant type.
///
/// A Parquet column annotated with the VARIANT logical type carries a self-describing binary
/// value whose shape can differ from row to row: a scalar, a timestamp, an array, a nested
/// object, … `RowReader.getVariant(...)` surfaces it as a `PqVariant`. From there you branch on
/// `type()`, navigate objects and arrays with `asObject()` / `asArray()`, and pull leaves out with
/// the matching `as*()` accessor. The same vocabulary nests all the way down, so one recursive
/// method renders any value.
///
/// This example walks a gallery of one-value fixtures, one per Variant type, and prints each
/// through that single [#render] method. The fixtures come from the Apache `parquet-testing`
/// corpus (see the README for the file-to-case mapping).
///
/// Follows the "Read Variant Columns" guide:
///   https://hardwood.dev/latest/how-to/variant/
/// API reference: https://hardwood.dev/api/1.0.0.Final/
public final class Main {

    /// The gallery, in printing order. Each fixture holds a single row with one `var` VARIANT value.
    private static final List<String> GALLERY = List.of(
            "null", "boolean", "int64", "double", "decimal", "string", "binary",
            "date", "time", "timestamp_utc", "timestamp_local", "uuid", "array", "object");

    public static void main(String[] args) throws Exception {
        for (String name : GALLERY) {
            PqVariant value = readVariant(name + ".parquet");
            // The type() tag tells you how to read the value; render() does exactly that, recursively.
            System.out.printf("%-16s %-20s %s%n", name, value.type(), render(value));
        }
    }

    /// Render any Variant value as a string, recursing into objects and arrays. The `type()` tag
    /// selects the matching accessor — read a leaf with the wrong one and it throws.
    private static String render(PqVariant v) {
        return switch (v.type()) {
            case NULL -> "null";
            case BOOLEAN_TRUE, BOOLEAN_FALSE -> String.valueOf(v.asBoolean());
            case INT8, INT16, INT32, INT64 -> String.valueOf(v.asLong());
            case FLOAT, DOUBLE -> String.valueOf(v.asDouble());
            case DECIMAL4, DECIMAL8, DECIMAL16 -> v.asDecimal().toString();
            case STRING -> "\"" + v.asString() + "\"";
            case BINARY -> "0x" + HexFormat.of().withUpperCase().formatHex(v.asBinary());
            case DATE -> v.asDate().toString();
            case TIME_NTZ -> v.asTime().toString();
            // UTC-adjusted tags read back as an Instant; wall-clock (NTZ) tags as a LocalDateTime.
            case TIMESTAMP, TIMESTAMP_NANOS -> v.asTimestamp().toString();
            case TIMESTAMP_NTZ, TIMESTAMP_NTZ_NANOS -> v.asLocalTimestamp().toString();
            case UUID -> v.asUuid().toString();
            case OBJECT -> renderObject(v.asObject());
            case ARRAY -> renderArray(v.asArray());
        };
    }

    /// Render an object as `{field: value, …}`. Walking it by `getFieldName(i)` keeps this generic;
    /// each field value comes back from `getVariant(name)` as another `PqVariant` to recurse on.
    private static String renderObject(PqVariantObject obj) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < obj.getFieldCount(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            String field = obj.getFieldName(i);
            out.append(field).append(": ").append(render(obj.getVariant(field)));
        }
        return out.append("}").toString();
    }

    /// Render an array as `[a, b, …]`. Elements are heterogeneous `PqVariant`s — recurse on each.
    private static String renderArray(PqVariantArray array) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < array.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(render(array.get(i)));
        }
        return out.append("]").toString();
    }

    /// Open a gallery fixture (a committed classpath resource) and return its single `var` value.
    /// InputFile.of(ByteBuffer) reads straight from memory, so the example needs no files on disk.
    private static PqVariant readVariant(String resource) throws IOException {
        ByteBuffer bytes = loadResource(resource);
        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(bytes));
                RowReader rows = reader.rowReader()) {
            rows.next();
            return rows.getVariant("var");
        }
    }

    private static ByteBuffer loadResource(String resource) {
        try (InputStream in = Main.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the classpath");
            }
            return ByteBuffer.wrap(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
