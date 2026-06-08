# Metadata Explorer

Describe a Parquet file from its **footer alone**, with [Hardwood](https://hardwood.dev) — no row
data is ever decoded. A Parquet file ends with a footer: a compact index of the schema, the row
groups, and, for each column chunk, its codec, on-disk sizes, null count, and min/max statistics.
Query engines read this footer first to decide
what to skip; this example prints it so you can see what a reader sees before touching a single
page. Uses one month of the public NYC Yellow Taxi trip-record dataset (~61 MB, 3.7 million rows),
downloaded automatically on first run.

## What you'll learn

- Open a file and read its footer with `ParquetFileReader.open(...)`, then reach the parsed metadata
  through `getFileMetaData()` and `getFileSchema()` — opening parses only the footer, so none of this
  reads or decodes column pages.
- Read file-level facts off `FileMetaData`: format `version()`, `numRows()`, `createdBy()`, and the
  embedded `keyValueMetadata()` (here, the Arrow schema the writer stored).
- Walk the schema as leaf `ColumnSchema`s, each with a **physical** type (`INT32`, `DOUBLE`,
  `BYTE_ARRAY`, …) and an optional **logical** type that gives those bytes meaning (a `BYTE_ARRAY`
  that is a `String`, an `INT64` that is a UTC timestamp).
- Descend `FileMetaData` → `RowGroup` → `ColumnChunk` → `ColumnMetaData`: per row group its row count
  and byte size, and per column chunk its codec, compressed/uncompressed sizes, value and null
  counts, and `Statistics` (min/max).
- Decode raw `Statistics` min/max bytes by the column's physical type — Parquet stores them in PLAIN
  encoding (little-endian for numerics, raw bytes for `BYTE_ARRAY`), so the same bytes mean different
  things depending on the type.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd metadata-explorer
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd metadata-explorer
docker compose run --rm --build metadata-explorer
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

The file summary and schema come first, then every row group with each of its column chunks
(abbreviated here):

```
== File ==
Format version : 2
Total rows     : 3,724,889
Row groups     : 4
Created by     : parquet-cpp-arrow version 16.1.0
Key-value metadata : 1 entry
  ARROW:schema = /////8gEAAAQAAAAAAAKAAwABgAFAAgACgAAAAABBAAMAAAACAAIAAAABAAIAAAABAAAABQAAABcBAAA…

== Schema ==
  VendorID                   INT32  [OPTIONAL]
  tpep_pickup_datetime       INT64 as TimestampType[isAdjustedToUTC=false, unit=MICROS]  [OPTIONAL]
  passenger_count            INT64  [OPTIONAL]
  trip_distance              DOUBLE  [OPTIONAL]
  store_and_fwd_flag         BYTE_ARRAY as StringType[]  [OPTIONAL]
  ...

== Row group 1 of 4 ==
Rows: 1,048,576   Size: 28,821,036 B   Columns: 20

  VendorID  (INT32)
    codec=ZSTD  247,603 B -> 133,912 B (1.8x)  encodings=[PLAIN, RLE, RLE_DICTIONARY]
    values=1,048,576  nulls=0  min=1  max=7

  trip_distance  (DOUBLE)
    codec=ZSTD  1,605,939 B -> 1,449,622 B (1.1x)  encodings=[PLAIN, RLE, RLE_DICTIONARY]
    values=1,048,576  nulls=0  min=-0.0  max=36610.27

  store_and_fwd_flag  (BYTE_ARRAY)
    codec=ZSTD  4,147 B -> 2,452 B (1.7x)  encodings=[PLAIN, RLE, RLE_DICTIONARY]
    values=1,048,576  nulls=0  min="N"  max="Y"
  ...
```

The exact figures (sizes, statistics, the writer string) come straight from the file, so they
reflect whatever month you downloaded.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/metadataexplorer/Main.java) is short and linear —
start there. [`Datasets.java`](src/main/java/dev/hardwood/examples/metadataexplorer/Datasets.java)
downloads the sample file.

- **Opening reads only the footer.** `ParquetFileReader.open(...)` parses the footer and nothing
  else; `getFileMetaData()` and `getFileSchema()` then hand back the already-parsed structures. The
  whole example runs without opening a single data page — that's the point of a footer.
- **Physical vs. logical type.** The physical type is how bytes are stored on disk; the logical type
  (when present) is what they mean. `tpep_pickup_datetime` is physically an `INT64` but logically a
  microsecond `Timestamp`; `store_and_fwd_flag` is a `BYTE_ARRAY` that is logically a `String`. A
  column with no logical type — like `VendorID` — is just its physical type.
- **The metadata tree.** `FileMetaData.rowGroups()` is a list of `RowGroup`s; each `RowGroup` lists a
  `ColumnChunk` per leaf column; each `ColumnChunk` carries a `ColumnMetaData` with the codec, sizes,
  counts, and statistics. Hardwood models these as plain records, so reading them is just calling
  accessors.
- **Statistics are raw bytes you decode by type.** `Statistics.minValue()` / `maxValue()` are
  `byte[]` in Parquet's PLAIN encoding — fixed-width little-endian for numerics, raw bytes for
  `BYTE_ARRAY`. The same bytes decode differently per physical type, so `decode(...)` switches on it.
  A chunk that is entirely null (some columns are, in the later row groups) writes no min/max, which
  shows up as `(absent)` next to a null count equal to the value count.
- **Why this metadata earns its keep.** Sizes tell a reader how much I/O each column costs; min/max
  let it skip a whole chunk whose range can't match a predicate (`WHERE fare_amount > 1000` can rule
  out a chunk whose max is 50 without reading it).
- **Timestamps print as raw epoch microseconds.** The example decodes by *physical* type, so an
  `INT64` timestamp shows as the underlying microsecond count rather than a formatted date — that is
  exactly what the footer stores.
- **Compression codecs are optional dependencies.** These taxi files are ZSTD-compressed, and even
  reading the footer touches the codec registry, so this example declares
  `com.github.luben:zstd-jni` (see [`pom.xml`](pom.xml)).

## Learn more

- [Inspect File Metadata](https://hardwood.dev/latest/how-to/metadata/) ·
  [How a Parquet File Is Laid Out](https://hardwood.dev/latest/concepts/parquet-layout/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.CR2/>
