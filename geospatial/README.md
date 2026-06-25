# Geospatial

Read a Parquet **GEOMETRY** column and push a **bounding-box filter** down to the row-group level
with [Hardwood](https://hardwood.dev). Parquet's geospatial layer stores geometries as
[WKB](https://en.wikipedia.org/wiki/Well-known_text_representation_of_geometry#Well-known_binary)
bytes in a column tagged with the GEOMETRY (planar) or GEOGRAPHY (geodesic) logical type, and records
a per-row-group bounding box in `GeospatialStatistics`. A spatial query consults those boxes to skip
whole row groups it can't match — without decoding their geometries.

The example reads a tiny fixture of nine world cities, grouped three-per-continent into three row
groups, and asks for the cities inside a box over Europe.

## What you'll learn

- Recognize a geospatial column from the schema via `ColumnSchema.logicalType()` —
  `LogicalType.GeometryType` (planar) vs. `LogicalType.GeographyType` (geodesic) — and read its CRS.
- Read each row group's bounding box from `RowGroup` → `ColumnChunk.metaData().geospatialStatistics()`
  → `GeospatialStatistics.bbox()` (`BoundingBox` with `xmin`/`xmax`/`ymin`/`ymax`, optional Z/M).
- Push a spatial filter down with `FilterPredicate.intersects(column, xmin, ymin, xmax, ymax)`, which
  drops row groups whose box is disjoint from the query box.
- Decode WKB to coordinates with **JTS** (`jts-core`) — Hardwood returns the raw geometry bytes and
  does not decode them, so WKB parsing is the application's (here, JTS's) job.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd geospatial
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd geospatial
docker compose run --rm --build geospatial
```

The GeoParquet fixture is committed with the example, so it runs fully offline — no download.

## Expected output

```
world-cities.parquet — 9 cities across 3 row groups

== Geospatial column ==
  location : GEOMETRY (planar)   CRS: OGC:CRS84
  values are WKB-encoded; Hardwood returns the raw bytes, JTS decodes them

== Per-row-group bounding boxes (the pushdown index) ==
  row group 0: lon [  -0.12,   13.40]  lat [ 48.85,  52.52]  (3 cities)
  row group 1: lon [  72.87,  139.69]  lat [ 19.07,  39.91]  (3 cities)
  row group 2: lon [-118.24,  -74.00]  lat [ 34.05,  41.87]  (3 cities)

== Cities intersecting Europe [lon -25..45, lat 35..72] ==
  London     (lon  -0.12, lat 51.50)
  Paris      (lon   2.35, lat 48.85)
  Berlin     (lon  13.40, lat 52.52)
  3 of 9 rows matched — the disjoint row groups were skipped by bbox
```

Only row group 0's box overlaps the Europe query box, so the reader skips the Asia and Americas
groups entirely — `intersects` matched 3 of 9 rows without decoding the other six geometries.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/geospatial/Main.java) is short and linear — start
there.

- **The logical type names the column.** Geometry lives in an ordinary `BINARY` column; what marks it
  geospatial is the logical type. `describeGeometryColumn` walks the schema and matches
  `LogicalType.GeometryType` / `GeographyType` — the same way you'd detect any logical type — and
  reads the CRS off it. An absent CRS means the OGC:CRS84 (WGS 84 lon/lat) default.
- **Bounding boxes are the spatial index.** Each row group's geometry chunk carries a
  `GeospatialStatistics` with a `BoundingBox`. `printRowGroupBoundingBoxes` prints them so the
  pushdown is visible: the three boxes sit on three continents, and only Europe's overlaps the query.
- **`intersects` pushes down to the row group.** `FilterPredicate.intersects(...)` keeps a row group
  only if its box overlaps the query box. It is **coarse-grained**: it skips disjoint row groups but
  returns *every* row in a surviving group, so for exact per-row geometry tests you still filter the
  decoded geometries yourself. (`intersects` also can't be negated — `not(intersects(...))` throws.)
- **Hardwood doesn't decode WKB; JTS does.** `getBinary("location")` hands back the raw WKB bytes,
  and `org.locationtech.jts.io.WKBReader` turns them into a `Geometry` — here a `Point` whose `getX()`
  / `getY()` are longitude / latitude. Keeping WKB decoding in a dedicated library (see
  [`pom.xml`](pom.xml)) is the usual division of labor.

## The fixture

[`world-cities.parquet`](src/main/resources/world-cities.parquet) is copied verbatim from Hardwood's
own test resources —
[`core/src/test/resources/geospatial_e2e_test.parquet`](https://github.com/hardwood-hq/hardwood/blob/main/core/src/test/resources/geospatial_e2e_test.parquet)
— renamed for readability. It holds nine cities as WKB points in a GEOMETRY column, laid out three
per row group so the bounding-box pushdown has something to skip.

## Learn more

- How-to: [Read Geospatial Columns](https://hardwood.dev/latest/how-to/geospatial/)
- How-to: [Filter, Project, Limit, and Split](https://hardwood.dev/latest/how-to/query-controls/) ·
  [File Metadata](https://hardwood.dev/latest/how-to/metadata/)
- Parquet format: [Geospatial definitions](https://github.com/apache/parquet-format/blob/master/Geospatial.md)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
