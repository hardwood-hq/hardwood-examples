/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.nested;

import java.util.PrimitiveIterator;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.row.PqList;
import dev.hardwood.row.PqLongList;
import dev.hardwood.row.PqMap;
import dev.hardwood.row.PqStruct;

/// Reading nested data — structs, lists, and maps — with the **Row API**.
///
/// A `RowReader` walks one record at a time and hands back lightweight views of nested fields:
/// `getList`, `getStruct`, and `getMap`. From those you read fields with the same typed
/// accessors you'd use on a flat column (`getString`, `getLong`, …), so nested data reads just
/// like flat data — one level down.
///
/// The example does three small, real tasks over three tiny fixtures:
///   1. **Address book** — print each owner's contacts and flag the ones with no phone number.
///   2. **Telemetry** — sum each device's readings through unboxed primitive list views.
///   3. **Settings** — resolve a few keys out of typed maps, looking values up by key.
///
/// For the columnar counterpart — reading the same shapes column-by-column through the layer
/// model — see the sibling `layer-model` example.
public final class Main {

    public static void main(String[] args) throws Exception {
        addressBook(Datasets.sample(Datasets.ADDRESS_BOOK));
        telemetry(Datasets.sample(Datasets.PRIMITIVE_LISTS));
        settings(Datasets.sample(Datasets.MAP_TYPES));
    }

    /// 1) Structs and lists. Each row has an `owner`, a `list<string>` of phone numbers, and a
    /// `list<struct<name, phoneNumber>>` of contacts. We print a directory and count, across
    /// everyone, how many contacts are missing a phone number.
    private static void addressBook(InputFile file) throws Exception {
        System.out.println("=== Address book ===");
        int missingNumbers = 0;

        try (ParquetFileReader reader = ParquetFileReader.open(file);
             RowReader rows = reader.rowReader()) {

            while (rows.hasNext()) {
                rows.next();
                System.out.println(rows.getString("owner"));

                // getList returns a flyweight over this row's list. An *empty* list is still a
                // list (size 0); only a truly absent value is null, so guard optional columns.
                PqList phones = rows.getList("ownerPhoneNumbers");
                System.out.println("  phones: " + (phones == null || phones.isEmpty() ? "(none)" : phones.strings()));

                // contacts is optional too, so guard it like phones above. structs() then views
                // the list as PqStruct flyweights; read each struct's fields by name. phoneNumber
                // is optional — getString returns null when it is absent.
                PqList contacts = rows.getList("contacts");
                if (contacts == null || contacts.isEmpty()) {
                    System.out.println("  contacts: (none)");
                } else {
                    System.out.println("  contacts:");
                    for (PqStruct contact : contacts.structs()) {
                        String number = contact.getString("phoneNumber");
                        if (number == null) {
                            missingNumbers++;
                        }
                        System.out.printf("    - %-16s %s%n", contact.getString("name"),
                                number == null ? "(no number on file)" : number);
                    }
                }
            }
        }
        System.out.println("Contacts with no phone number: " + missingNumbers);
    }

    /// 2) Primitive lists, no boxing. Each row is a device with a `list<long>` of readings.
    /// `longs()` is a typed view whose iterator yields primitive `long`s, so the sum never
    /// allocates a boxed `Long`. `ints()` and `doubles()` work the same way for those types.
    private static void telemetry(InputFile file) throws Exception {
        System.out.println("\n=== Telemetry ===");
        long grandTotal = 0;

        try (ParquetFileReader reader = ParquetFileReader.open(file);
             RowReader rows = reader.rowReader()) {

            while (rows.hasNext()) {
                rows.next();
                int id = rows.getInt("id");

                PqList readings = rows.getList("long_list");
                if (readings == null) {
                    System.out.printf("  device %d: no readings%n", id);
                    continue;
                }

                PqLongList longs = readings.longs();
                long sum = 0;
                for (PrimitiveIterator.OfLong it = longs.iterator(); it.hasNext(); ) {
                    sum += it.nextLong();
                }
                grandTotal += sum;
                System.out.printf("  device %d: %d readings, sum=%d%n", id, longs.size(), sum);
            }
        }
        System.out.println("Total across all devices: " + grandTotal);
    }

    /// 3) Maps and typed key lookups. Each row carries a `map<string,string>` of profile fields
    /// and a `map<int,long>` of numeric settings. You can iterate entries, or look a value up by
    /// key — and the key overload is typed, so the int-keyed map takes a primitive `int`.
    private static void settings(InputFile file) throws Exception {
        System.out.println("\n=== Settings ===");

        try (ParquetFileReader reader = ParquetFileReader.open(file);
             RowReader rows = reader.rowReader()) {

            while (rows.hasNext()) {
                rows.next();
                int id = rows.getInt("id");

                // String-keyed lookup with a default when the key is absent.
                PqMap profile = rows.getMap("string_map");
                Object greeting = profile.containsKey("greeting") ? profile.getValue("greeting") : "(default)";

                // Int-keyed lookup — the int overload avoids boxing the key. getValue returns the
                // decoded value (a Long here, since the map's values are int64).
                PqMap numericSettings = rows.getMap("int_map");
                String setting2 = numericSettings.containsKey(2) ? numericSettings.getValue(2).toString() : "unset";

                System.out.printf("  user %d: greeting=%s, int_map[2]=%s, %d numeric setting(s)%n",
                        id, greeting, setting2, numericSettings.size());
            }
        }
    }
}
