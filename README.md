# Hardwood Examples

This repository contains small, self-contained, runnable examples for
[Hardwood](https://hardwood.dev) — a fast, minimal-dependency implementation of Apache Parquet.

Each example is a complete, standalone Maven project in its own folder, focused on **one**
concept. It brings its own `pom.xml` and Maven wrapper and pulls everything it needs,
so all you do to see it work is **run its `Main` class**.

## Prerequisites

- **JDK 21 or newer**. That's it for the Maven path (the bundled `./mvnw` fetches Maven).
- *(Optional)* **Docker**, only for examples that need infrastructure (e.g. S3 via MinIO)
  or if you prefer running an example in a container.

## How to run any example

Each example is independent. Run one with its bundled Maven wrapper from inside its folder:

```shell
cd hello-hardwood
./mvnw -q compile exec:java
```

Examples that ship a `Dockerfile` / `docker-compose.yaml` can also be run with Docker:

```shell
cd hello-hardwood
docker compose run --rm --build hello-hardwood
```

`run --rm` runs the container once and removes it on exit, so no stopped container is left behind.

## Build every example at once

A root aggregator `pom.xml` compiles all examples in one go — handy for a quick "does
everything still build" check. Run it from the repository root with the bundled wrapper:

```shell
./mvnw -q compile
```

It only coordinates the build; each example stays independent and still builds and runs on its own.

Most examples download their sample data into the example's `data/` folder on first run and reuse
it after that — it comes from the public
[NYC TLC Yellow Taxi trip-record dataset](https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page).
Examples that need a small, purpose-built fixture instead commit it with the example, so they run
fully offline.

## Examples

| Example | What it shows |
| --- | --- |
| [Hello Hardwood](./hello-hardwood) | The core reader API end to end — open a file, read its schema and footer, iterate rows with typed accessors, and sum a column. |
| [Column Analytics](./column-analytics) | Columnar reads — project the columns you need, pull them in batches of primitive arrays, and aggregate null-aware without boxing. |
| [Concurrent Column Consumer](./concurrent-column-consumer) | Hand column batches off to a thread pool so heavy per-value processing scales across cores, not just the decoding. |
| [Query Controls](./query-controls) | Predicate pushdown — push filters, projections, limits, and pagination into the scan so it reads only the rows and columns that matter. |
| [Metadata Explorer](./metadata-explorer) | Footer metadata — read the schema, logical types, row groups, and column statistics without decoding a single row. |
| [Multi-File](./multi-file) | Open many files as one logical dataset and scan them with a shared thread pool you size yourself. |
| [Byte Buffer Source](./byte-buffer-source) | In-memory reads — read Parquet straight from a `ByteBuffer` (an HTTP body, a blob, a `byte[]`), one buffer or many as a single dataset. |
| [Typed Accessors](./typed-accessors) | Logical types — map dates, timestamps, decimals, UUIDs, and intervals onto natural Java types, recognized from the schema. |
| [Nested Data](./nested-data) | Nested columns — read structs, lists, and maps with the row API, including unboxed primitive lists and typed map keys. |
| [Layer Model](./layer-model) | The repeated/struct layer model — aggregate nested data from its offsets and validity, without materializing row objects. |
| [Variant Columns](./variant-columns) | Semi-structured data — decode flexible, JSON-like VARIANT values, whatever shape they take. |
| [Geospatial](./geospatial) | Reading geospatial columns — recognize a GEOMETRY column, read its points, and push a bounding-box filter down to skip row groups that can't intersect the query. |
| [Reading from S3](./reading-from-s3) | Read Parquet straight from Amazon S3 (or any S3-compatible store) — configure an `S3Source`, fetch byte ranges instead of whole objects, and open a whole bucket as one dataset. Runs offline against MinIO. |

## License

Copyright The original authors. Licensed under the [Apache License, Version 2.0](./LICENSE.txt).
