# Hardwood Examples

This repository contains small, self-contained, runnable examples for
[Hardwood](https://hardwood.dev) — a fast, minimal-dependency implementation of Apache Parquet.

Each example lives in its own top-level folder and demonstrates **one** concept. Every
folder is a complete, standalone Maven project — its own `pom.xml` and Maven wrapper — that
pulls everything it needs (dependencies **and** sample data), so the only thing you do to
see an example work is **run its `Main` class**.

**For getting started, begin with [`./hello-hardwood`](./hello-hardwood).**

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

Sample data is downloaded into the example's `data/` folder on first run and reused after
that. It comes from the public
[NYC TLC Yellow Taxi trip-record dataset](https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page).

## Examples

| Example | Description | Concepts touched |
| --- | --- | --- |
| [Hello Hardwood](./hello-hardwood) | The absolute basics — open a Parquet file, inspect its schema and footer metadata, read a few rows, and sum a column. | `ParquetFileReader`, `RowReader`, `ColumnReader`, footer metadata, typed accessors, null handling |
| [Column Analytics](./column-analytics) | Aggregate a month of trips the columnar way — project a few columns, read batches of primitive arrays, compute totals and averages null-aware. | `ColumnReaders`, `ColumnProjection`, batched primitive arrays, hoisted `Validity` null checks |

<!-- New examples: add a `| [Name](./folder) | one-line description | key APIs/concepts |` row above. -->

## License

Copyright The original authors. Licensed under the [Apache License, Version 2.0](./LICENSE.txt).
