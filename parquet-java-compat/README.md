# Parquet-Java Compatibility

Read Parquet with Apache **parquet-java**'s `ParquetReader<Group>` API — except every
`org.apache.parquet.*` type is a drop-in shim from
[Hardwood](https://hardwood.dev)'s `hardwood-parquet-java-compat` module, so the same code
runs on Hardwood's reader unchanged. Point existing parquet-java code at this module and get
Hardwood's performance without a rewrite. Uses one month of the public NYC Yellow Taxi
trip-record dataset (January 2026), downloaded automatically on first run.

> **Experimental.** The `hardwood-parquet-java-compat` module's API surface and behavior may
> change in future releases without prior deprecation.
>
> **Mutually exclusive with parquet-java.** The module ships its own type shims in the
> `org.apache.parquet.*` namespace, so it **cannot** share a classpath with the real
> `parquet-java` — pick one or the other. This example depends on the compat module alone.

## What you'll learn

- Open a file with parquet-java's `ParquetReader.builder(new GroupReadSupport(), path).build()`
  and pull rows in the classic `while ((record = reader.read()) != null)` loop.
- Read fields off a `Group` the parquet-java way — `getInteger(field, 0)`, `getDouble(field, 0)`,
  `getLong(field, 0)` — and detect a null column with `getFieldRepetitionCount(field) == 0`.
- Use the Hadoop `Path` shim (`new Path("...")`) with **no Hadoop dependency** on the classpath.
- Push a predicate down with the standard `FilterApi` / `FilterCompat` classes so only rows from
  matching row groups are decoded.

## Prerequisite: build the compat module

Unlike the rest of Hardwood, `hardwood-parquet-java-compat` is **not published to Maven
Central yet**, so there is nothing for Maven to download. Until it ships, build it from
source — this is the supported way to run this example for now.

[`build-compat.sh`](build-compat.sh) does it for you: it clones Hardwood at the exact
version this example targets and installs the compat module (and the Hardwood modules it
needs) into your local Maven repository (`~/.m2`).

```shell
cd parquet-java-compat
./build-compat.sh
```

Run it once before the first run below, and again after a Hardwood version bump. It is
idempotent — the clone lands in a gitignored `.hardwood-src/` and is reused on reruns.

## Run it

JDK 21+ and the compat module installed (above). Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
./mvnw -q compile exec:java
```

**Docker** — the container reuses the artifacts `build-compat.sh` installed on the host
(mounted read-only from `~/.m2`), so run that script first:

```shell
docker compose run --rm --build parquet-java-compat
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

```
== First 5 trips (parquet-java ParquetReader<Group>, backed by Hardwood) ==
  schema: 20 columns
    VendorID
    tpep_pickup_datetime
    ...
    cbd_congestion_fee

  vendor=2  distance=0.97 mi  $15.86  passengers=1
  vendor=1  distance=0.90 mi  $13.65  passengers=0
  vendor=1  distance=1.40 mi  $18.95  passengers=0
  vendor=2  distance=5.58 mi  $55.56  passengers=4
  vendor=2  distance=2.16 mi  $23.10  passengers=0

== First 5 trips over 20 mi and $100 (FilterApi pushdown) ==
  40.14 mi  $198.70
  20.69 mi  $131.49
  49.83 mi  $490.63
  23.37 mi  $111.79
  23.09 mi  $146.69
```

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/compat/Main.java) is short and linear — start
there. [`Datasets.java`](src/main/java/dev/hardwood/examples/compat/Datasets.java) downloads the
sample file.

- **Every imported type is a shim.** `ParquetReader`, `GroupReadSupport`, `Group`, `FilterApi`,
  `FilterCompat`, and the Hadoop `Path` all come from `hardwood-parquet-java-compat`, which
  re-implements the `org.apache.parquet.*` (and `org.apache.hadoop.*`) interfaces on top of
  Hardwood. The read loop is byte-for-byte the parquet-java original; only the dependency changed.
- **No Hadoop on the classpath.** The compat module brings its own `Path` and `Configuration`
  shims, so reading a local file needs no Hadoop jars. (`pom.xml` declares only the compat module
  and the ZSTD codec.)
- **Nullable columns report zero repetitions.** Following parquet-java semantics, a null field has
  `getFieldRepetitionCount(field) == 0`; guard the typed accessor with that check rather than
  calling `getLong` on an absent value.
- **Filter pushdown is the standard API.** `FilterApi.and(gt(doubleColumn("trip_distance"), 20.0), …)`
  builds a `FilterPredicate`, and `FilterCompat.get(...)` wraps it for
  `.withFilter(...)`. Hardwood uses row-group statistics to skip groups that cannot match.
- **Compression codecs are optional dependencies.** These taxi files are ZSTD-compressed, so this
  example declares `com.github.luben:zstd-jni` (see [`pom.xml`](pom.xml)). Remove it and Hardwood
  fails with a message naming the exact dependency to add.

## Learn more

- How-to: [Parquet-java compatibility](https://hardwood.dev/latest/how-to/compat/)
- Concept: [Compatibility philosophy](https://hardwood.dev/latest/concepts/compatibility-philosophy/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
