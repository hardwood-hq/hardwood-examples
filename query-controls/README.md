# Query Controls

Narrow a read with **query controls**, using [Hardwood](https://hardwood.dev) — a predicate, a
column projection, a row limit, and byte-range splits. Each control is *pushed down* into the scan
rather than applied afterwards in Java: the predicate is evaluated as pages decode (and whole row
groups whose statistics rule them out are skipped), the projection keeps unselected columns off
disk entirely, `head(n)` stops the scan once enough rows match, and `skip(n)` pages through the
matches with an OFFSET. Uses one month of the public NYC Yellow Taxi trip-record dataset (~61 MB,
3.7 million rows), downloaded automatically on first run.

## What you'll learn

- Build a filtered, projected, limited scan with `reader.buildRowReader().filter(...)
  .projection(...).head(n).build()` — one `RowReader` that applies all three controls together.
- Compose a `FilterPredicate` from leaf comparisons and logical operators: the numeric overloads
  (`gt`/`ltEq`/`eq` on `int`, `long`, `double`), `and(...)`, and `not(...)`.
- Filter a logical-type column: bound a wall-clock `TIMESTAMP` with `Instant` values
  (`gtEq` … `lt`) to express a half-open time window.
- Test set membership with `in(...)` over integers and `inStrings(...)` over strings.
- Page through a filtered result set with `skip(offset).head(limit)` — OFFSET/LIMIT over the
  matching stream — producing successive, non-overlapping pages.
- See **three-valued null logic** in action — a comparison against `NULL` is `UNKNOWN`, so null
  rows match neither `> 6` nor `<= 6`, and only `isNull(...)` / `isNotNull(...)` select on
  presence.
- Split a file into byte ranges with `RowGroupPredicate.byteRange(start, end)`, so two scans read
  disjoint row groups — the basis for handing a file to parallel readers.

## Run it

No setup beyond a JDK 21+. Pick either Maven or Docker.

**Maven** (from this folder, using the bundled Maven wrapper):

```shell
cd query-controls
./mvnw -q compile exec:java
```

**Docker:**

```shell
cd query-controls
docker compose run --rm --build query-controls
```

The taxi data is downloaded into a local `data/` folder on first run and reused after that.

## Expected output

```
Scanning 3,724,889 trips with pushed-down query controls.

== Trips > 10 mi, fare <= $100, not vendor 2 — first 5 matches ==
  2026-01-01T00:04:59  vendor=1  10.20 mi  $ 47.10
  2026-01-01T00:49:04  vendor=1  19.10 mi  $ 80.00
  ...

== Pickups on Jan 15 — first 5 matches ==
  2026-01-15T00:59:44  $ 14.90
  ...

== Store-and-forwarded trips by vendor 1 or 6 ==
  1,828 trips

== passenger_count null handling ==
  total rows        : 3,724,889
  value present     : 2,636,831
  value is NULL     : 1,088,058
  value > 6         : 7  (NULLs excluded — they are neither > 6 nor <= 6)
  present + NULL     = 3,724,889  (== total)

== Paginating trips > 20 mi, 8 per page ==
  -- page 1 (skip 0, head 8) --
    #1   2026-01-01T00:21:27   20.20 mi  $  70.00
    ...
  -- page 2 (skip 8, head 8) --
    #9   2026-01-01T00:31:40   23.37 mi  $  85.60
    ...
  -- page 3 (skip 16, head 8) --
    #17  2026-01-01T00:42:50   20.01 mi  $  76.50
    ...

== Byte-range splits (file is 64,165,080 bytes, split at 32,082,540) ==
  row group 0: starts at byte 4 -> first half
  row group 1: starts at byte 18,033,983 -> first half
  row group 2: starts at byte 35,616,671 -> second half
  row group 3: starts at byte 54,047,133 -> second half
  first half  : 2,097,152 rows
  second half : 1,627,737 rows
  combined     = 3,724,889 rows  (== total)

(processed in: 663 ms)
```

The exact rows and figures come straight from the file, so they reflect whatever month you
downloaded.

## How it works

[`Main.java`](src/main/java/dev/hardwood/examples/querycontrols/Main.java) is short and linear —
each query control gets its own method. [`Datasets.java`](src/main/java/dev/hardwood/examples/querycontrols/Datasets.java)
downloads the sample file.

- **Filter + project + limit in one scan.** `buildRowReader()` returns a builder; `filter(...)`
  attaches the predicate, `projection(...)` lists the columns to decode, and `head(n)` caps the
  result. Hardwood applies all three while it reads: it skips row groups whose statistics can't
  satisfy the predicate, decodes only the projected columns from the rest, and stops at `n` matches
  — so an early `head` over a selective filter touches very little of the file.
- **Predicates compose.** `FilterPredicate.and(a, b, c)` joins leaf predicates, `not(...)` negates
  one, and the comparison factories (`gt`, `ltEq`, `eq`, …) are overloaded per type. The right
  overload is chosen by the value you pass — `gt("trip_distance", 10.0)` is the `double` form,
  `eq("VendorID", 2)` the `int` form.
- **Logical types filter by their natural Java type.** `tpep_pickup_datetime` is a local-wall-clock
  `TIMESTAMP`, so its values read back with `getLocalTimestamp`. The predicate takes an `Instant`,
  whose value Hardwood matches against the stored timestamp; `gtEq(start)` with `lt(end)` gives a
  half-open `[start, end)` window. There are matching overloads for `LocalDate`, `LocalTime`,
  `BigDecimal`, and `UUID` columns.
- **Set membership.** `in("VendorID", 1, 6)` matches any of the listed integers; `inStrings(
  "store_and_fwd_flag", "Y")` does the same for strings — both shorthand for an `or` of equalities.
- **Pagination is OFFSET + LIMIT.** `head(limit)` is the page size and `skip(offset)` drops the
  first `offset` *matching* rows — an OFFSET over the filtered stream, not over the file. Paging
  `offset = 0, pageSize, 2*pageSize, …` walks the result set in stable, non-overlapping windows,
  so each page continues where the last ended. The running `#` index is the row's position in the
  whole result set, which makes that alignment visible.
- **Three-valued null logic.** SQL-style: comparing `NULL` to anything yields `UNKNOWN`, never
  `true`. So a null `passenger_count` satisfies neither `> 6` nor `<= 6`, and the only way to select
  null rows is `isNull(...)`. The output makes this concrete — *value present* plus *value is NULL*
  add back up to the total row count.
- **Byte-range splits.** `RowGroupPredicate.byteRange(start, end)` keeps only the row groups whose
  start offset falls in `[start, end)`. Splitting the file at its midpoint puts the earlier row
  groups in one scan and the later ones in another; because each row group lands in exactly one
  range, the two scans read disjoint rows whose counts add up to the whole file. That is how a query
  engine carves one file into independent units of work for parallel readers.

## Learn more

- [Filter, Project, Limit, and Split](https://hardwood.dev/latest/how-to/query-controls/) ·
  [Row Selection](https://hardwood.dev/latest/concepts/row-selection/)
- Tutorial: [Your first read](https://hardwood.dev/latest/tutorial/first-read/)
- API reference (Javadoc): <https://hardwood.dev/api/1.0.0.Final/>
