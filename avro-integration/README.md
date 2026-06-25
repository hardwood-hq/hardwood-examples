# Avro Integration

Read Parquet rows as Avro [`GenericRecord`](https://avro.apache.org/docs/) with
[Hardwood](https://hardwood.dev) — the shape Kafka and Spark consumers already speak. Hardwood
derives an Avro schema from the Parquet footer and materializes each row as a `GenericRecord`, so
code written against Avro can read Parquet unchanged. Uses one month of the public NYC Yellow Taxi
trip-record dataset (January 2026), downloaded automatically on first run.

## What you'll learn

- Create an Avro reader with `AvroReaders.rowReader(fileReader)` (all rows) or
  `AvroReaders.buildRowReader(fileReader)` for a configurable one.
- Read the **derived Avro schema** with `AvroRowReader.getSchema()` — the contract a downstream
  consumer codes against, built from the footer without decoding any rows.
- Iterate `GenericRecord` values with `hasNext()` / `next()` and pull fields with `record.get(...)`.
- Know the **Java types Avro hands back** — `Integer`, `Long`, `Double`, `String`, and a `Long` of
  microseconds for a TIMESTAMP column — and map them to your own types.
- Push the same query controls as the native reader into the Avro reader: a `projection` (which
  narrows both the scan and the derived schema), a `filter`, and a `head` / `tail` row limit.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd avro-integration
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd avro-integration
docker compose run --rm --build avro-integration
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

```
== Derived Avro schema (6 fields) ==
  VendorID               ["null","int"]
  tpep_pickup_datetime   ["null",{"type":"long","logicalType":"local-timestamp-micros"}]
  passenger_count        ["null","long"]
  trip_distance          ["null","double"]
  store_and_fwd_flag     ["null","string"]
  total_amount           ["null","double"]

== First 5 trips (as Avro GenericRecord) ==
  2026-01-01T00:54:04  vendor=2  0.97 mi  $15.86  passengers=1

  Value representations for this record:
    VendorID               -> java.lang.Integer
    tpep_pickup_datetime   -> java.lang.Long
    passenger_count        -> java.lang.Long
    trip_distance          -> java.lang.Double
    store_and_fwd_flag     -> java.lang.String
    total_amount           -> java.lang.Double
  2026-01-01T00:34:04  vendor=1  0.90 mi  $13.65  passengers=0
  ...

== First 5 trips over 20 mi and $100 (projection + filter + head) ==
  2025-12-31T23:58:58  40.14 mi  $198.70
  ...

== Last 3 trips in the file (projection + tail) ==
  2026-01-31T23:40:23  6.84 mi  $33.96
  ...
```

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/avro/Main.java) is short and linear — start there.
[`Datasets.java`](src/main/java/dev/hardwood/examples/avro/Datasets.java) downloads the sample file.

- **The schema is derived from the footer.** `getSchema()` returns the Avro `Schema` Hardwood
  builds from the Parquet schema, narrowed to the projected columns. Every column in this file is
  optional, so each field is an Avro union `["null", T]`; a required column would be a bare `T`. A
  TIMESTAMP column carries its Avro logical type (`local-timestamp-micros` here) on a `long`.
- **Records are materialized lazily.** `next()` advances the underlying reader and builds one
  `GenericRecord` for the current row, holding the projected fields; you pull the ones you need with
  `record.get("name")`.
- **Values are raw Avro representations.** `record.get(...)` returns `Object`, backed by a
  `java.lang.Integer` / `Long` / `Double` / `String`. A TIMESTAMP column is a `Long` of
  microseconds since the epoch — Avro does not hand you a `java.time` type, so the example converts
  it to a `LocalDateTime` itself. Nullable columns return their value or `null`.
- **Query controls carry over.** `buildRowReader(...)` takes the same `projection(...)`,
  `filter(...)`, `head(n)`, and `tail(n)` controls as the native
  [query controls](https://hardwood.dev/latest/how-to/query-controls/). A `projection` narrows both
  the scan and the derived schema; `tail` is single-file only and cannot combine with `head` or
  `filter`.

## Learn more

- [Avro integration](https://hardwood.dev/latest/how-to/avro/) ·
  [Query controls](https://hardwood.dev/latest/how-to/query-controls/)
- API reference (Javadoc):
  [`AvroReaders`](https://hardwood.dev/api/1.0.0.Final/dev/hardwood/avro/AvroReaders.html) ·
  [`AvroRowReader`](https://hardwood.dev/api/1.0.0.Final/dev/hardwood/avro/AvroRowReader.html)
- [Apache Avro documentation](https://avro.apache.org/docs/)
