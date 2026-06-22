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

## Build every example at once

A root aggregator `pom.xml` compiles all examples in one go — handy for a quick "does
everything still build" check. Run it from the repository root with the bundled wrapper:

```shell
./mvnw -q compile
```

This is purely a build coordinator: each example stays a fully autonomous Maven project that
does not inherit from the aggregator, so you can still build and run any one of them in complete
isolation from its own folder.

Most examples download their sample data into the example's `data/` folder on first run and reuse
it after that — it comes from the public
[NYC TLC Yellow Taxi trip-record dataset](https://www.nyc.gov/site/tlc/about/tlc-trip-record-data.page).
Examples that need a small, purpose-built fixture instead commit it with the example, so they run
fully offline.

## Examples

Start with **Hello Hardwood**, then dip into whichever group fits what you're doing.

### Getting started

| Example | Description |
| --- | --- |
| [Hello Hardwood](./hello-hardwood) | The absolute basics — open a Parquet file, inspect its schema and footer metadata, read a few rows, and sum a column.<br/>**Concepts:** `ParquetFileReader`, `RowReader`, `ColumnReader`, footer metadata, typed accessors, null handling |

### Core — flat data, fast reads

| Example | Description |
| --- | --- |
| [Column Analytics](./column-analytics) | Aggregate a month of trips the columnar way — project a few columns, read batches of primitive arrays, compute totals and averages null-aware.<br/>**Concepts:** `ColumnReaders`, `ColumnProjection`, batched primitive arrays, hoisted `Validity` null checks |
| [Concurrent Column Consumer](./concurrent-column-consumer) | Scale consumption, not just decoding — project a column, pull batches of primitive arrays, and fan each batch's heavy per-value work across a thread pool, with a single-threaded baseline to prove the win.<br/>**Concepts:** `ColumnReaders`, `ColumnProjection`, batch array hand-off, `ExecutorService` range split + partial reduce, when consumer-side threading actually helps |
| [Query Controls](./query-controls) | Narrow a read with pushed-down query controls — filter, project, limit, paginate, and split by byte range — so the scan touches only the rows and columns it must.<br/>**Concepts:** `FilterPredicate` (numeric + logical-type overloads, `in`/`inStrings`, `not`), `ColumnProjection`, `head`, `skip`, `RowGroupPredicate.byteRange`, three-valued null logic |
| [Metadata Explorer](./metadata-explorer) | Describe a Parquet file from its footer alone — version, schema with physical/logical types, and per row group every column chunk's codec, sizes, null count, and stats.<br/>**Concepts:** `FileMetaData`, `FileSchema`, `RowGroup`, `ColumnChunk`, `ColumnMetaData`, `Statistics`, key-value metadata |
| [Multi-File](./multi-file) | Read three months of trips as one logical dataset — per-file footer counts, a single cross-file scan, and a thread pool you size yourself.<br/>**Concepts:** `Hardwood.openAll`, `InputFile.ofPaths`, `ParquetFileReader.isMultiFile`, `HardwoodContext`, shared pool + cross-file prefetch |
| [Byte Buffer Source](./byte-buffer-source) | Read Parquet straight from an in-memory `ByteBuffer` — the "I already have the bytes" path for an HTTP body, a blob store, or a `byte[]` — and read several in-memory blobs as one dataset.<br/>**Concepts:** `InputFile.of(ByteBuffer)`, `InputFile.ofBuffers(...)`, `ParquetFileReader.openAll`, `isMultiFile`, buffer `duplicate()` for independent cursors |

### Types & data shapes

| Example | Description |
| --- | --- |
| [Typed Accessors](./typed-accessors) | Decode Parquet's logical types to their natural Java types — dates, times, UTC vs. local timestamps, decimals, UUIDs, intervals, FLOAT16, and JSON/BSON — and recognize a column's type from the schema.<br/>**Concepts:** `getDate`/`getTime`/`getTimestamp`/`getLocalTimestamp`/`getDecimal`/`getUuid`/`getInterval`, `PqInterval`, `ColumnSchema.logicalType()` (JSON/BSON/FLOAT16/NULL) |
| [Nested Data](./nested-data) | Read structs, lists, and maps with the Row API — walk an address book, sum telemetry through unboxed primitive lists, and resolve typed map keys.<br/>**Concepts:** `RowReader`, `getStruct`/`getList`/`getMap`, `PqStruct`, `PqList`, `PqIntList`/`PqLongList`/`PqDoubleList` (no boxing), `PqMap` typed key lookups |
| [Layer Model](./layer-model) | Aggregate nested columns without materializing rows — count list and map items from offsets, and see a list's and map's outer group fold into one repeated layer.<br/>**Concepts:** `ColumnReader`, `getLayerCount`/`getLayerKind`, `getLayerOffsets`/`getLayerValidity`, `getLeafValidity`, `LayerKind` (REPEATED vs STRUCT) |
| [Variant Columns](./variant-columns) | Read semi-structured, JSON-like VARIANT columns — a gallery of every Variant type, each branched on its type tag and rendered through one recursive method, including nested objects/arrays and the two timestamp flavors.<br/>**Concepts:** `RowReader.getVariant`, `PqVariant`, `PqVariantObject`, `PqVariantArray`, `VariantType`, `asObject`/`asArray`, the `as*()` leaf accessors, `asTimestamp` vs `asLocalTimestamp` |

## License

Copyright The original authors. Licensed under the [Apache License, Version 2.0](./LICENSE.txt).
