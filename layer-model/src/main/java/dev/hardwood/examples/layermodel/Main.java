/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.layermodel;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ColumnReader;
import dev.hardwood.reader.LayerKind;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.Validity;

/// Reading nested data column-by-column through the **layer model**.
///
/// Parquet stores only flat leaf columns; it rebuilds nesting from Dremel's repetition and
/// definition levels. The layer model hands you that structure already decoded, as a stack of
/// **layers** above each leaf — without ever materializing row objects, so you can aggregate over
/// nested columns at columnar speed. Each layer is one of:
///   - **REPEATED** — a `list` or `map`: carries *offsets* (where each parent's children start)
///     plus *validity* (which parents are null vs. present-but-empty).
///   - **STRUCT** — an optional struct: carries *validity* only (one child per parent, no offsets).
///
/// The key simplification: a `list`'s two schema groups (the `LIST`-annotated group **and** its
/// inner `repeated` group) — and a `map`'s two groups (`MAP` + `key_value`) — each collapses into a
/// **single** REPEATED layer. So the very same offset-walking code summarizes a list and a map.
/// See: https://hardwood.dev/latest/concepts/nested-columns/
public final class Main {

    public static void main(String[] args) throws Exception {
        InputFile addressBook = Datasets.sample(Datasets.ADDRESS_BOOK);
        InputFile maps = Datasets.sample(Datasets.MAP_TYPES);

        // A list and a map each present exactly ONE repeated layer, so one routine handles both.
        System.out.println("=== Repeated layers: list and map fold the same way ===");
        try (ParquetFileReader reader = ParquetFileReader.open(addressBook)) {
            summarizeRepeated(reader, "ownerPhoneNumbers.list.element", "phone numbers", "owners");
        }
        try (ParquetFileReader reader = ParquetFileReader.open(maps)) {
            summarizeRepeated(reader, "int_map.key_value.value", "settings", "users");
        }

        // A list of structs stacks a STRUCT layer on top of the REPEATED layer; the leaf's own
        // validity tells null values apart from missing parents.
        System.out.println("\n=== Struct layer: find contacts with no phone number ===");
        try (ParquetFileReader reader = ParquetFileReader.open(addressBook)) {
            countMissingLeaves(reader, "contacts.list.element.phoneNumber");
        }
    }

    /// Summarizes a repeated (list- or map-) leaf straight from its single REPEATED layer:
    /// how many items in total, and how each parent fared — non-empty, present-but-empty, or null.
    /// The offsets slice the flat value array per parent; the layer validity flags null parents.
    private static void summarizeRepeated(ParquetFileReader reader, String leafPath, String item, String parent) {
        int parents = 0;
        int totalItems = 0;
        int empties = 0;
        int nulls = 0;
        int busiest = 0;

        try (ColumnReader col = reader.columnReader(leafPath)) {
            while (col.nextBatch()) {
                int[] offsets = col.getLayerOffsets(0);
                Validity present = col.getLayerValidity(0);
                int records = col.getRecordCount();
                for (int r = 0; r < records; r++) {
                    int count = offsets[r + 1] - offsets[r];
                    parents++;
                    totalItems += count;
                    busiest = Math.max(busiest, count);
                    if (present.isNull(r)) {
                        nulls++;            // the whole list/map is absent
                    } else if (count == 0) {
                        empties++;          // present, but holds nothing
                    }
                }
            }
        }

        System.out.printf("%s: %d %s across %d %s (most in one: %d; empty: %d; null: %d)%n",
                leafPath, totalItems, item, parents, parent, busiest, empties, nulls);
    }

    /// Counts null leaf values under a list-of-structs leaf. Its layer stack is REPEATED (the
    /// list) then STRUCT (the optional struct) — the struct does NOT fold away — and the leaf's
    /// own validity marks which values are null. Here: contacts that have no phone number.
    private static void countMissingLeaves(ParquetFileReader reader, String leafPath) {
        int total = 0;
        int missing = 0;

        try (ColumnReader col = reader.columnReader(leafPath)) {
            StringBuilder stack = new StringBuilder();
            for (int layer = 0; layer < col.getLayerCount(); layer++) {
                stack.append(layer == 0 ? "" : " > ").append(col.getLayerKind(layer));
            }
            while (col.nextBatch()) {
                Validity leaf = col.getLeafValidity();
                int values = col.getValueCount();
                total += values;
                for (int i = 0; i < values; i++) {
                    if (leaf.isNull(i)) {
                        missing++;
                    }
                }
            }
            System.out.printf("%s: layers [%s]%n", leafPath, stack);
        }

        System.out.printf("  %d contacts, %d with no phone number%n", total, missing);
    }
}
