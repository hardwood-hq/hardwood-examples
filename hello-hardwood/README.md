# Hello Hardwood

The "hello world" of [Hardwood](https://hardwood.dev): open a Parquet file, inspect its
schema, and read a few rows. Uses one month of the public NYC Yellow Taxi trip-record
dataset (~61 MB, 3.7 million rows), downloaded automatically on first run.

## What you'll learn

- Open a Parquet file with `ParquetFileReader.open(InputFile.of(path))`.
- Read the row count and schema from the footer — **without decoding any rows**.
- Iterate rows with a `RowReader` and read columns with typed accessors (`getInt`, `getDouble`,
  `getLong`), checking `isNull` before a primitive accessor on a nullable column.
- Use **index-based** accessors (`getInt(idx)`) instead of by-name (`getInt("VendorID")`) in the
  read loop — looking the index up once is faster than a per-row name lookup.
- Aggregate a column the **columnar** way with a `ColumnReader` — read it in batches of
  primitive arrays and use `Validity` to skip nulls, no boxing per value.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd hello-hardwood
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd hello-hardwood
docker compose run --rm --build hello-hardwood
```

The taxi data is downloaded into a local `data/` folder on first run and reused after
that.

## Expected output

```
Total rows: 3724889
Row groups: 4
  VendorID : INT32
  tpep_pickup_datetime : INT64
  ...
  cbd_congestion_fee : DOUBLE

First 5 trips:
vendor=2  distance=0.97 mi  passengers=1
vendor=1  distance=0.90 mi  passengers=0
vendor=1  distance=1.40 mi  passengers=0
vendor=2  distance=5.58 mi  passengers=4
vendor=2  distance=2.16 mi  passengers=0

Total fares: $77493536.48

(processed in: … ms)
```

The `processed in` timer measures the reading work only — not the one-time data download —
so the exact figure varies by machine and run.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/hello/Main.java) is short and linear —
start there. [`Datasets.java`](src/main/java/dev/hardwood/examples/hello/Datasets.java)
downloads the sample file.

- **Metadata is cheap.** `getFileMetaData()` and `getFileSchema()` read only the file
  footer, so you can inspect a file's size and shape without decoding row data.
- **Typed accessors.** `getInt` / `getLong` / `getDouble` read a column at the current row.
  For nullable columns like `passenger_count`, check `isNull(...)` before a primitive accessor
  (object accessors like `getString` / `getTimestamp` return `null` instead).
- **Compression codecs are optional dependencies.** These taxi files are ZSTD-compressed,
  so this example declares `com.github.luben:zstd-jni` (see [`pom.xml`](pom.xml)). Remove
  it and Hardwood fails with a message naming the exact dependency to add.

## Learn more

- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- [Getting Started](https://hardwood.dev/latest/getting-started/) ·
  [Row-Oriented Reading](https://hardwood.dev/latest/usage/row-reader/) ·
  [File Metadata](https://hardwood.dev/latest/usage/metadata/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.CR1/>
