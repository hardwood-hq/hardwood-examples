# Multi-File

Read several Parquet files as **one logical dataset** with [Hardwood](https://hardwood.dev).
`Hardwood.openAll(...)` returns a single reader over many files: they share one schema and one
`RowReader` that walks every row of every file in a single loop, share one thread pool, and the
reader prefetches across the file boundary so the scan never stalls at a seam. Uses three
consecutive months (January–March 2026) of the public NYC Yellow Taxi trip-record dataset
(~191 MB, 11 million rows), downloaded automatically on first run.

## What you'll learn

- Open many files as one reader with `Hardwood.openAll(InputFile.ofPaths(...))`, and confirm it
  with `reader.isMultiFile()`.
- Get the dataset's *true* row count by scanning one `RowReader` across all files — a multi-file
  reader's footer reports only the first file's count, so a scan is how you total the whole dataset.
- Size the read thread pool yourself with `HardwoodContext.create(n)` and open on it with
  `ParquetFileReader.openAll(paths, context)` — the one pool is shared across every file.
- Watch a wide all-column scan get faster as the pool grows (MB/s by pool size), because the
  per-chunk decode and decompression split across that shared pool.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd multi-file
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd multi-file
docker compose run --rm --build multi-file
```

The three monthly files are downloaded into a local `data/` folder on first run and reused after
that.

## Expected output

```
== Combined with openAll (multi-file: true) ==
  files        : 3
  scanned rows : 11,077,206

== Wide scan (all columns) throughput by pool size, 10 cores ==
   1 threads :  1,442.1 ms     132.3 MB/s
   2 threads :    553.1 ms     344.8 MB/s
   4 threads :    311.5 ms     612.3 MB/s
   8 threads :    220.6 ms     864.6 MB/s

(processed in: 3,099 ms)
```

The exact rows and figures come straight from the files, so they reflect whatever months you
downloaded. Each pool size is timed once, so the numbers are noisy — the 1-thread pass also absorbs
the JVM's one-time JIT warm-up since it runs first — and they vary by machine. The shape is the
point, not the exact figures: decode throughput climbs as the pool grows, then flattens once memory
bandwidth, not thread count, is the limit.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/multifile/Main.java) is short and linear — one
method per step. [`Datasets.java`](src/main/java/dev/hardwood/examples/multifile/Datasets.java)
downloads the three monthly files.

- **One logical dataset.** `Hardwood.openAll(InputFile.ofPaths(jan, feb, mar))` returns a single
  `ParquetFileReader`; `isMultiFile()` confirms it spans more than one file. A single `RowReader`
  from `buildRowReader()` then walks every row of every file in one loop. A multi-file reader reports
  only the *first* file's footer, so its `getFileMetaData().numRows()` is just that file's count —
  scanning the combined reader is how you total the whole dataset. That count projects a single
  column, so it is inexpensive and decode-light: it shows the files are joined, not how fast they
  read.
- **Sizing the pool, and why it matters.** `HardwoodContext.create(n)` builds a pool of `n`
  threads, and `ParquetFileReader.openAll(paths, context)` opens the dataset on it. The one pool
  serves every file — there is no per-file pool — so it is the single knob for read concurrency. The
  context owns the pool, so closing it shuts the pool down; close the reader first (try-with —
  resources closes in reverse order). To make the knob visible, the example runs a *wide* scan —
  every column via `reader.columnReaders(ColumnProjection.all())` — at pool sizes 1, 2, 4, and 8,
  timing each. Decoding all columns, and especially the zstd decompression, is CPU-heavy and splits
  across the pool, so throughput roughly doubles from 1 to 2 threads and keeps climbing until memory
  bandwidth — not thread count — caps it. A single narrow column wouldn't show this: there is too
  little decode work to parallelize, and the per-row consumer loop runs on the calling thread
  regardless of pool size.
- **Shared pool and cross-file prefetch.** Whatever the pool size, all files in the dataset are read
  on that one pool, and the reader prefetches across file boundaries: while it decodes the last row
  group of one month, it is already fetching the first of the next. That is why a multi-file scan
  stays on the fast path instead of going idle at each seam.

## Learn more

- [Read Multiple Files as One Dataset](https://hardwood.dev/latest/how-to/multi-file/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
