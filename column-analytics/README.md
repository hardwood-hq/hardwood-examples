# Column Analytics

Aggregate a whole month of taxi trips the **columnar** way with [Hardwood](https://hardwood.dev):
project a handful of columns, read them in batches of primitive arrays, and compute totals and
averages without ever touching a row. Uses one month of the public NYC Yellow Taxi trip-record
dataset (~61 MB, 3.7 million rows), downloaded automatically on first run.

## What you'll learn

- Read several columns together with `ColumnReaders`, created from a
  `ColumnProjection.columns(...)` so only those columns are fetched and decoded.
- Drive every column in lockstep with `ColumnReaders.nextBatch()` and read each batch as a typed
  primitive array (`getLongs()`, `getDoubles()`) — no per-row method calls, no boxing.
- Aggregate null-aware: hoist each column's `Validity.hasNulls()` out of the inner loop and skip
  null slots so sums and averages stay honest.
- See *why* there are two reader APIs: the same job over a `RowReader` would make ~18M per-value
  accessor calls; the columnar path turns the inner loop into plain array arithmetic.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd column-analytics
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd column-analytics
docker compose run --rm --build column-analytics
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

```
Analyzed 3,724,889 trips across the month.

Avg passengers per trip : 1.26
Avg trip distance       : 6.46 mi
Total fares             : $77,493,536.48
Total tips              : $9,715,040.34
Avg tip as % of fare    : 12.5%
Avg total per trip      : $29.18

(processed in: … ms)
```

The `processed in` timer measures the read and aggregation only — not the one-time data
download — so the exact figure varies by machine and run.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/columnanalytics/Main.java) is short and linear —
start there. [`Datasets.java`](src/main/java/dev/hardwood/examples/columnanalytics/Datasets.java)
downloads the sample file.

- **Projection reads less.** `ColumnProjection.columns("passenger_count", "trip_distance", …)` tells
  Hardwood to fetch only those five columns; the other ~15 columns in the file are never read off
  disk or decoded.
- **Batches of primitive arrays.** Each `nextBatch()` advances all five readers together and hands
  back the batch as `long[]` / `double[]`. The inner loop is array arithmetic — the columnar path's
  whole point versus a row-at-a-time `getDouble(...)` call.
- **Nullable in the schema vs. null in the batch.** A column's `RepetitionType` is its static
  contract: a `REQUIRED` column can never hold a null, an `OPTIONAL` one may. That decides which
  columns need a guard at all — and here all five are `OPTIONAL`, so all five are guarded, even
  though only `passenger_count` carries nulls in this month's file. Don't drop a guard because one
  downloaded file happens to be null-free.
- **Hoisted null checks.** A column's `Validity` marks which slots are null. `hasNulls()` is a
  per-batch test, so calling it once per batch short-circuits to the fast path in O(1); inside the
  loop a local boolean gates the per-element check. `passenger_count`'s nulls are concentrated in the
  later batches, so its early batches still take the fast path — and its nulls are excluded from the
  passenger average.
- **Pick the loop shape to match null density.** `Validity` supports three ways to skip nulls:
  direct `isNull(i)` / `isNotNull(i)` (cold paths — debug, small batches), the hoisted `hasNulls()`
  form used here (the default for analytical hot loops, where most batches are non-null), and
  word-wise `Validity.words()` (for **null-dense** columns, iterating only the present bits via
  `Long.numberOfTrailingZeros`). None of these columns is mostly null, so `words()` would give no
  measurable win — it pays off when most values are null.
- **Compression codecs are optional dependencies.** These taxi files are ZSTD-compressed, so this
  example declares `com.github.luben:zstd-jni` (see [`pom.xml`](pom.xml)). Remove it and Hardwood
  fails with a message naming the exact dependency to add.

## Going further

- **Parallelize across batches.** The arrays a batch hands back are freshly allocated on each
  `nextBatch()` — a later batch never overwrites an earlier one — so you can hand a batch's arrays
  to another thread and keep aggregating while it works. The reader itself stays a single-threaded
  cursor: only the loop thread calls `nextBatch()`; it's the *returned arrays* that are detached and
  safe to read elsewhere.
- **Tune the batch size.** Use the builder form, `reader.buildColumnReaders(projection).batchSize(n).build()`,
  to cap how many **records** each batch holds. For flat columns like these, one record is one value,
  so `getRecordCount()` and `getValueCount()` agree; for repeated columns a record can carry many
  leaf values, so `getValueCount()` can exceed the batch size — size per-value buffers off
  `getValueCount()`.

## Learn more

- [Column-Oriented Reading](https://hardwood.dev/latest/how-to/column-reader/) ·
  [RowReader vs. ColumnReader](https://hardwood.dev/latest/concepts/reader-models/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
