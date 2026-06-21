# Variant Columns

Read semi-structured, JSON-like **VARIANT** columns with [Hardwood](https://hardwood.dev). A
Parquet column annotated with the VARIANT logical type carries a self-describing binary value —
a scalar, a timestamp, an array, a nested object, … — whose shape can differ from row to row.
`RowReader.getVariant(...)` surfaces it as a `PqVariant` you inspect and navigate with one small,
recursive vocabulary.

This example is a **gallery**: a one-value fixture per Variant type, each printed through the same
recursive `render` method. The fixtures are tiny committed files, so it runs fully offline.

## What you'll learn

- Read a VARIANT value with `RowReader.getVariant(...)` and branch on its `type()`
  (`VariantType.STRING`, `INT64`, `DECIMAL8`, `TIMESTAMP`, `OBJECT`, `ARRAY`, …).
- Pull every leaf type out with the matching `as*()` accessor: `asBoolean`, `asLong`, `asDouble`,
  `asDecimal`, `asString`, `asBinary`, `asDate`, `asTime`, `asUuid`.
- Handle the two timestamp flavors: a UTC-adjusted tag reads back as an `Instant` (`asTimestamp`),
  a wall-clock (NTZ) tag as a `LocalDateTime` (`asLocalTimestamp`) — calling the wrong one throws.
- Navigate nested data with `asObject()` / `asArray()`, walking an object by `getFieldCount()` /
  `getFieldName(i)` / `getVariant(name)` — and see that every nested value is itself a `PqVariant`,
  so one recursive method renders any shape.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd variant-columns
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd variant-columns
docker compose run --rm --build variant-columns
```

There is nothing to download — the VARIANT fixtures are bundled with the example.

## Expected output

```
null             NULL                 null
boolean          BOOLEAN_TRUE         true
int64            INT64                9876543210
double           DOUBLE               14.3
decimal          DECIMAL8             123456789.987654321
string           STRING               "iceberg"
binary           BINARY               0x0A0B0C0D
date             DATE                 2024-11-07
time             TIME_NTZ             12:33:54.123456
timestamp_utc    TIMESTAMP            2024-11-07T12:33:54.123456Z
timestamp_local  TIMESTAMP_NTZ        2024-11-07T12:33:54.123456
uuid             UUID                 f24f9b64-81fa-49d1-b74e-8c09a6e31c56
array            ARRAY                ["comedy", "drama"]
object           OBJECT               {c: {a: 34, b: "iceberg"}, d: -0.0}
```

Each line is `fixture name`, the Variant `type()` tag, and the rendered value. The last two show
the recursion: an array of strings, and an object whose `c` field is itself an object.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/variantcolumns/Main.java) is short and linear —
start there.

- **One value, many shapes.** `getVariant("var")` returns a `PqVariant`. Its `type()` is the tag
  that decides everything: `render` is a single `switch` over `VariantType` that the compiler checks
  for exhaustiveness, so it handles every Variant type and recurses on objects and arrays.
- **A leaf per accessor.** Each scalar tag maps to its `as*()` method — `asLong` for the integer
  tags, `asDecimal` for the decimals, `asBinary` for raw bytes, `asUuid` for a UUID, and so on. Read
  a value with the wrong accessor and it throws, so the tag is what you switch on first.
- **Two timestamp tags.** The Variant format splits timestamps along the same `isAdjustedToUTC`
  boundary as Parquet's TIMESTAMP logical type. `timestamp_utc` carries the UTC-adjusted tag, so
  `asTimestamp` returns an `Instant`; `timestamp_local` carries the wall-clock (NTZ) tag, so
  `asLocalTimestamp` returns a `LocalDateTime`.
- **Nested navigation.** `asObject()` gives a `PqVariantObject` (walked here generically with
  `getFieldCount` / `getFieldName`, though typed getters like `getString("b")` work when you know the
  field); `asArray()` gives an indexed, iterable `PqVariantArray`. Both hand back `PqVariant`
  children, so the same `render` recursion goes all the way down.

## The fixtures

Each fixture in [`src/main/resources/`](src/main/resources/) is a tiny Parquet file with a single
row: a `var` group of two `BYTE_ARRAY` children, `metadata` and `value`, annotated with the VARIANT
logical type. They are vendored from the Apache
[`parquet-testing`](https://github.com/apache/parquet-testing/tree/master/shredded_variant) corpus,
renamed by the type they carry:

| Fixture | Variant type | Source case |
| --- | --- | --- |
| `null.parquet` | `NULL` | `case-047` |
| `boolean.parquet` | `BOOLEAN_TRUE` | `case-048` |
| `int64.parquet` | `INT64` | `case-056` |
| `double.parquet` | `DOUBLE` | `case-060` |
| `decimal.parquet` | `DECIMAL8` | `case-070` |
| `string.parquet` | `STRING` | `case-075` |
| `binary.parquet` | `BINARY` | `case-074` |
| `date.parquet` | `DATE` | `case-062` |
| `time.parquet` | `TIME_NTZ` | `case-076` |
| `timestamp_utc.parquet` | `TIMESTAMP` | `case-064` |
| `timestamp_local.parquet` | `TIMESTAMP_NTZ` | `case-066` |
| `uuid.parquet` | `UUID` | `case-081` |
| `array.parquet` | `ARRAY` | `case-001` |
| `object.parquet` | `OBJECT` | `case-044` |

`array.parquet` and `object.parquet` are *shredded* — part of the value lives in a typed sibling
column — but reassembly is transparent: `getVariant` returns the same `PqVariant` either way, so the
reader code never has to care.

## Learn more

- [Read Variant Columns](https://hardwood.dev/latest/how-to/variant/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- [Variant binary encoding spec](https://github.com/apache/parquet-format/blob/master/VariantEncoding.md)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.CR2/>
