# Byte Buffer Source

Read Parquet straight from an in-memory `ByteBuffer` with [Hardwood](https://hardwood.dev) — the
"I already have the bytes" path. Sometimes the Parquet bytes never touch the local filesystem: they
arrive as an HTTP response body, come back from a blob store (S3/GCS/Azure), or are simply a
`byte[]` handed to your service. `InputFile.of(ByteBuffer)` reads them directly, no temp file
required. Uses one month of the public NYC Yellow Taxi trip-record dataset (~61 MB, 3.7 million
rows), downloaded automatically on first run — purely to get some real Parquet bytes, which the
example then reads from memory.

## What you'll learn

- Wrap bytes you already hold in a `ByteBuffer` and open them with `InputFile.of(ByteBuffer)` —
  `open()` is a no-op and nothing holds a file handle, since the data is already in memory.
- Read metadata and rows from that in-memory reader exactly as you would over a file path; only the
  source of the bytes differs.
- Read several in-memory blobs as one dataset with `InputFile.ofBuffers(...)` — one file per buffer,
  the in-memory counterpart of `InputFile.ofPaths(...)` — via `ParquetFileReader.openAll(...)`.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd byte-buffer-source
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd byte-buffer-source
docker compose run --rm --build byte-buffer-source
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

```
Loaded 64,165,080 bytes into memory.

== InputFile.of(ByteBuffer) — one in-memory file ==
  rows       : 3,724,889
  row groups : 4
  first 3 trips:
    vendor=2  distance=0.97 mi
    vendor=1  distance=0.90 mi
    vendor=1  distance=1.40 mi

== InputFile.ofBuffers(...) — several in-memory files as one dataset ==
  multi-file  : true
  buffers     : 2
  total miles : 48,093,136.0  (the same payload twice)
```

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/bytebuffersource/Main.java) is short and linear —
start there.
[`Datasets.java`](src/main/java/dev/hardwood/examples/bytebuffersource/Datasets.java) downloads the
sample file.

- **The bytes are the source.** `Files.readAllBytes(file)` stands in for "bytes I already have"; in
  a real service they'd come from an HTTP body or a blob store. `ByteBuffer.wrap(...)` makes them an
  `InputFile` input without a copy.
- **`of(ByteBuffer)` needs no resource acquisition.** Because the data is already in memory, the
  reader's `open()` does nothing and there is no file descriptor to manage. Everything else — footer
  metadata, the `RowReader`, columnar reads — is identical to the file-path path.
- **Hand off a view, not the buffer.** A `ByteBuffer` carries a read position, so the example passes
  `bytes.duplicate()` to each reader. That gives every reader its own independent cursor over the
  same shared bytes and keeps the original buffer reusable.
- **`ofBuffers(...)` is multi-file in memory.** It returns one `InputFile` per buffer, which
  `ParquetFileReader.openAll(...)` presents as a single logical dataset (`isMultiFile()` is `true`).
  This example passes the same payload twice just to show the API; each buffer would normally be a
  different blob. The example then folds a column across the whole dataset — summing `trip_distance`
  reads actual values from both buffers, so the total lands at twice a single file's mileage. See the
  [`multi-file`](../multi-file) example for more.
- **Compression codecs are optional dependencies.** These taxi files are ZSTD-compressed, so this
  example declares `com.github.luben:zstd-jni` (see [`pom.xml`](pom.xml)). Remove it and Hardwood
  fails with a message naming the exact dependency to add.

## Learn more

- [Configuration reference](https://hardwood.dev/latest/reference/configuration/) ·
  [Package structure](https://hardwood.dev/latest/reference/packages/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
