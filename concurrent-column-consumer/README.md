# Concurrent Column Consumer

Scale **consumption**, not just decoding, with [Hardwood](https://hardwood.dev): project a column,
pull batches of primitive arrays, and fan each batch's per-value work across a thread pool. The
point is that once `nextBatch()` returns a `double[]`, the caller owns it outright and can
parallelize freely — something the row reader's per-row cursor cannot offer. Uses one month of the
public NYC Yellow Taxi trip-record dataset (~61 MB, 3.7 million rows), downloaded automatically on
first run.

This is the parallel counterpart to [`column-analytics`](../column-analytics), which covers the
single-threaded columnar path.

## What you'll learn

- Project columns with `ColumnReaders` / `ColumnProjection.columns(...)` and drive them with
  `nextBatch()`, reading each batch as a typed primitive array (`getDoubles()`).
- Hand a batch's array off to an `ExecutorService`: split it into contiguous ranges, score each
  range on a worker thread, and reduce the per-range partials back into one result.
- Keep decode and consumption cleanly separated — the reader's cursor stays on the driving thread;
  only the *returned array* crosses the thread boundary.
- The deciding principle, stated honestly: **consumer-side threading only pays off when the
  per-value work is the bottleneck.** For a light fold the job goes memory-bandwidth-bound and
  threading the consumer buys nothing.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd concurrent-column-consumer
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd concurrent-column-consumer
docker compose run --rm --build concurrent-column-consumer
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

```
Scored 3,724,889 trips with a heavy per-value model (60 iterations each).

Worker threads (cores)  : 10
Model score (checksum)  : 7.239252e+06
Single-threaded consume : 5,360 ms
Parallel consume        : 933 ms
Speedup                 : 5.74x
```

Both paths decompose each batch into the same ranges and reduce the same partials in the same
order, so they return a bit-for-bit identical score — the program throws if they ever disagree. The
worker count, timings, and speedup track your machine's core count, so the exact figures vary; the
score itself is a synthetic checksum, not a money figure.

In this example the parallel consumer runs roughly **5–8× faster** than the single-threaded
baseline, but that number is not fixed — it depends entirely on the workload deferred to the
threads. The heavier the per-value work, the closer the speedup gets to your core count; lighten it
toward a plain fold, and the gain collapses, because the job becomes memory-bandwidth-bound rather
than CPU-bound (see "When does this actually help?" below).

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/concurrentcolumnconsumer/Main.java) is short and
linear — start there.
[`Datasets.java`](src/main/java/dev/hardwood/examples/concurrentcolumnconsumer/Datasets.java)
downloads the sample file.

- **The caller owns the batch.** Each `nextBatch()` allocates fresh arrays and hands them back; the
  reader keeps no reference to them, so a returned `double[]` is safe to read from any thread. That
  ownership is what makes consumer-side parallelism possible — the row reader's per-row cursor never
  detaches a value you can ship elsewhere.
- **Split, score, reduce.** For every batch the array is cut into one contiguous range per core.
  Each range is submitted to a shared `ExecutorService` (one fixed pool for the whole run, never one
  per batch), scores its slice, and returns a partial sum; the driving thread reduces the partials
  back into one total. Only the primitive arrays and two `int` bounds cross the thread boundary —
  the reader and its cursor never do.
- **A deliberately heavy consumer.** The per-value model is a perturbed Newton iteration run 60
  times — a stand-in for a real pricing model or feature transform. It is expensive *on purpose*:
  the example exists to show the parallel win, and the win is only real when the per-value work
  dominates.
- **When does this actually help?** Consumer-side threading pays off **only when the per-value
  computation is the bottleneck.** Turn `ITERATIONS` down toward zero — or replace the model with a
  plain `sum +=` — and the loop becomes memory-bandwidth-bound: every core waits on the same memory
  bus, so splitting the work across threads buys nothing and the coordination overhead can even make
  it slower. The heavy model here is what tips the balance, and the single-threaded baseline printed
  alongside is there to prove the win is real rather than assumed.
- **Same work, two ways, timed separately.** Each batch is consumed once single-threaded and once
  across the pool. The one-time decode is excluded from both timers, so the reported times isolate
  the threading effect — not the cost of reading the column off disk.
- **Compression codecs are optional dependencies.** These taxi files are ZSTD-compressed, so this
  example declares `com.github.luben:zstd-jni` (see [`pom.xml`](pom.xml)). Remove it and Hardwood
  fails with a message naming the exact dependency to add.

## Going further

- **Match the pool to the work.** This example sizes the pool to the core count because the consumer
  is CPU-bound. If your per-value work blocks (a network or disk call per value), a larger pool or a
  different executor fits better — but first confirm the consumer, not the decode, is the bottleneck.
- **Tune the batch size.** Use the builder form,
  `reader.buildColumnReaders(projection).batchSize(n).build()`, to cap how many records each batch
  holds. Larger batches amortize the per-batch submit/reduce overhead across more values.
- **Go null-aware.** The score here treats every decoded value uniformly. To exclude nulls from a
  real statistic, read `ColumnReader.getLeafValidity()` on the driving thread and pass the per-range
  null information alongside the array — see [`column-analytics`](../column-analytics) for the
  hoisted `Validity` pattern.

## Learn more

- [Column-Oriented Reading](https://hardwood.dev/latest/how-to/column-reader/) ·
  [RowReader vs. ColumnReader](https://hardwood.dev/latest/concepts/reader-models/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
