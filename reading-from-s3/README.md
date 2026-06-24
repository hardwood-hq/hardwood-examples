# Reading from S3

Read a Parquet file straight from Amazon S3 with [Hardwood](https://hardwood.dev) — no local copy,
streaming only the bytes you need. The same code works against any S3-compatible store (MinIO,
Cloudflare R2, GCS), since Hardwood speaks the S3 protocol, not an AWS-only SDK.

So you can run it with **no AWS account and no internet**, this example points at a
[MinIO](https://min.io) server running next to it in Docker. Spin it up, it seeds a bucket with the
public NYC Yellow Taxi dataset, and the reader streams rows back over S3. Switching to real Amazon
S3 changes only the `S3Source` configuration — see [Pointing at real AWS](#pointing-at-real-aws)
below.

## What you'll learn

- Build an [`S3Source`](https://hardwood.dev/api/1.0.0.CR2/dev/hardwood/s3/S3Source.html) for any
  S3-compatible endpoint — `endpoint`, `pathStyle`, `region`, and credentials via
  `S3Credentials.of(...)`.
- Open an object with `source.inputFile("s3://bucket/key")` and read it with the **exact same**
  `ParquetFileReader` / `RowReader` API you'd use on a local file.
- See that Hardwood fetches **byte ranges**, not whole objects: project two columns and the read
  transfers ~1.6 MB out of a 64 MB file. `S3InputFile` exposes `networkBytesFetched()` and
  `networkRequestCount()` so you can prove it.
- Open every object in a bucket as one logical dataset with
  `Hardwood.openAll(source.inputFilesInBucket(...))`.

## Run it

Everything runs in Docker — MinIO, the one-time data download, bucket seeding, and the reader:

```shell
cd reading-from-s3
docker compose up --build
```

The reader prints its output and exits. MinIO keeps running so you can re-run or browse the
console at <http://localhost:9001> (login `minioadmin` / `minioadmin`). When you're done:

```shell
docker compose down -v
```

The taxi file is downloaded into a named volume on first run and reused after that.

### Running the reader with Maven

The reader is an ordinary Maven project; only the MinIO infrastructure needs Docker. Start that
in the background, then run the app on your host (it defaults to `http://localhost:9000`):

```shell
docker compose up -d minio downloader seed   # bring up + seed MinIO
./mvnw -q compile exec:java                   # run the reader against it
```

## Expected output

```
== Reading s3://taxi/yellow_tripdata_2026-01.parquet ==
Total rows: 3724889
Columns: 20

First 5 trips:
  vendor=2  distance=0.97 mi
  vendor=1  distance=0.90 mi
  vendor=1  distance=1.40 mi
  vendor=2  distance=5.58 mi
  vendor=2  distance=2.16 mi

Projected 2 of 20 columns — fetched 1,649,074 bytes over 4 HTTP requests (the object itself is 64,165,080 bytes).

== Reading the whole 'taxi' bucket as one dataset ==
Scanned 7,449,778 rows across 2 objects.
```

The seed step uploads the same month under two keys, so the bucket holds two objects
(3,724,889 rows each) that `openAll` stitches into one 7,449,778-row dataset.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/s3/Main.java) is short and linear — start there.

- **One `S3Source`, reused.** It holds the endpoint, credentials, and a shared HTTP client. Build
  it once and open every object through it so connections and credentials are reused. MinIO needs
  **path-style** addressing (`{endpoint}/{bucket}/{key}`) rather than the virtual-hosted style AWS
  uses by default, so the builder sets `pathStyle(true)`.
- **An S3 object reads like a local file.** `source.inputFile(...)` returns an `S3InputFile` that
  implements the same `InputFile` interface as a local path. Everything downstream — the footer,
  the schema, the `RowReader` — is identical to the [Hello Hardwood](../hello-hardwood) example.
- **Range reads, not downloads.** Each read issues a signed HTTP GET with a byte-range header, so
  only the requested bytes move. Projecting two columns and stopping after five rows transfers a
  sliver of the object; the counters on `S3InputFile` report exactly how much.
- **Connection details come from the environment.** `S3_ENDPOINT`, `S3_ACCESS_KEY`, etc. default
  to the local MinIO, so the same build runs unchanged inside the Docker network
  (`http://minio:9000`) and from your host (`http://localhost:9000`).
- **Seeding is plain `mc`.** [`docker-compose.yaml`](docker-compose.yaml) downloads the dataset
  with `curl`, then uses the MinIO client (`mc`) to create the bucket and upload it. Hardwood is a
  reader, so writing the data into S3 is the job of separate tooling, not the example.

## Pointing at real AWS

Drop the MinIO-specific settings and give the `S3Source` a region and real credentials:

```java
S3Source source = S3Source.builder()
        .region("us-east-1")
        .credentials(S3Credentials.of(accessKeyId, secretKey))
        .build();
```

For production, avoid hard-coded keys. Add the `hardwood-aws-auth` module and use the standard AWS
credential chain (environment variables, `~/.aws/credentials`, EC2/ECS instance profiles, SSO):

```java
import dev.hardwood.aws.auth.SdkCredentialsProviders;

S3Source source = S3Source.builder()
        .region("us-east-1")
        .credentials(SdkCredentialsProviders.defaultChain())
        .build();
```

The same builder configures timeouts (`connectTimeout`, `requestTimeout`), retries (`maxRetries`),
a custom `HttpClient`, and a `rangeBacking(...)` cache for workloads that re-read the same ranges.
See the [S3 reference](https://hardwood.dev/latest/reference/s3/) for the full list.

## Learn more

- [Reading from S3](https://hardwood.dev/latest/how-to/s3/) ·
  [S3 reference](https://hardwood.dev/latest/reference/s3/)
- [Reading multiple files](https://hardwood.dev/latest/how-to/multi-file/) ·
  [Query controls](https://hardwood.dev/latest/how-to/query-controls/)
- [Getting Started](https://hardwood.dev/latest/getting-started/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.CR2/>
