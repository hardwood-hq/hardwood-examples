# Reading from S3

Read a Parquet file straight from Amazon S3 with [Hardwood](https://hardwood.dev) — no local copy,
streaming only the bytes you need. The same code works against any S3-compatible store (MinIO,
Cloudflare R2, GCS), since Hardwood speaks the S3 protocol, not an AWS-only SDK.

So you can run it with **no AWS account**, this example serves the data from a throwaway S3 server
([s3proxy](https://github.com/gaul/s3proxy)) that [Testcontainers](https://testcontainers.com)
starts in a Docker container next to it. It seeds a bucket with the public NYC Yellow Taxi dataset,
and the reader streams rows back over S3. Hardwood can't tell it apart from real S3 — it speaks the
same protocol. Switching to real Amazon S3 changes only the `S3Source` configuration — see
[Pointing at real AWS](#pointing-at-real-aws) below.

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

You need a running **Docker** daemon — Testcontainers uses it to start the S3 server. Then:

```shell
cd reading-from-s3
./mvnw -q compile exec:java
```

On first run it downloads the taxi file into a local `data/` folder (reused afterwards), starts the
S3 server, seeds it, and the reader streams rows back over S3. The server is removed when the run
finishes.

### Run it in a container

You can also run the whole example in Docker. Since the reader itself launches a container, this
mounts the Docker socket (see [`docker-compose.yaml`](docker-compose.yaml)):

```shell
docker compose run --rm --build reading-from-s3
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
  it once and open every object through it so connections and credentials are reused. s3proxy (like
  most non-AWS services) needs **path-style** addressing (`{endpoint}/{bucket}/{key}`) rather than
  the virtual-hosted style AWS uses by default, so the builder sets `pathStyle(true)`.
- **An S3 object reads like a local file.** `source.inputFile(...)` returns an `S3InputFile` that
  implements the same `InputFile` interface as a local path. Everything downstream — the footer,
  the schema, the `RowReader` — is identical to the [Hello Hardwood](../hello-hardwood) example.
- **Range reads, not downloads.** Each read issues a signed HTTP GET with a byte-range header, so
  only the requested bytes move. Projecting two columns and stopping after five rows transfers a
  sliver of the object; the counters on `S3InputFile` report exactly how much.
- **The S3 server is throwaway infrastructure.** [`S3Proxy.java`](src/main/java/dev/hardwood/examples/s3/S3Proxy.java)
  runs the s3proxy image via Testcontainers and seeds the bucket by copying the dataset in before
  startup. It exists only so the example has something to read from — it is *not* code you'd write
  in an app, where the data already lives in S3. Hardwood is a reader, so seeding is separate tooling.

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
