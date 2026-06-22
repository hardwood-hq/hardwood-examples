/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.geospatial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.io.WKBReader;

import dev.hardwood.InputFile;
import dev.hardwood.metadata.BoundingBox;
import dev.hardwood.metadata.GeospatialStatistics;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.RowGroup;
import dev.hardwood.reader.FilterPredicate;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.FileSchema;

/// Read a GEOMETRY column and push a bounding-box filter down to the row-group level.
///
/// Parquet's geospatial layer stores geometries as WKB bytes in a column annotated with the
/// GEOMETRY (planar) or GEOGRAPHY (geodesic) logical type, and records a per-row-group bounding box
/// in `GeospatialStatistics`. Hardwood reads that layer: it recognizes the logical type, exposes the
/// bounding boxes, and evaluates `FilterPredicate.intersects(...)` against them — so a spatial query
/// skips whole row groups whose box is disjoint from the query box without decoding them.
///
/// The filter is *coarse-grained*: it drops disjoint row groups but returns every row in a surviving
/// group, so a query box that clips a group still yields that group's far-away rows. Hardwood hands
/// back the raw WKB bytes and does not decode them — this example uses JTS (`jts-core`) for that, the
/// usual division of labor.
///
/// Follows the "Read Geospatial Columns" guide:
///   https://hardwood.dev/latest/how-to/geospatial/
/// API reference: https://hardwood.dev/api/1.0.0.CR2/
public final class Main {

    /// A small fixture of world-city point locations — see the README for its provenance.
    private static final String RESOURCE = "world-cities.parquet";

    /// A bounding box loosely covering Europe, as `[lon, lat]` in OGC:CRS84 (WGS 84) degrees:
    /// longitude west-to-east `-25..45`, latitude south-to-north `35..72`.
    private static final double EUROPE_XMIN = -25.0;
    private static final double EUROPE_YMIN = 35.0;
    private static final double EUROPE_XMAX = 45.0;
    private static final double EUROPE_YMAX = 72.0;

    public static void main(String[] args) throws Exception {
        ByteBuffer bytes = loadResource(RESOURCE);

        try (ParquetFileReader reader = ParquetFileReader.open(InputFile.of(bytes))) {
            long totalRows = reader.getFileMetaData().numRows();
            List<RowGroup> rowGroups = reader.getFileMetaData().rowGroups();
            System.out.printf("%s — %,d cities across %d row groups%n",
                    RESOURCE, totalRows, rowGroups.size());

            ColumnSchema geometry = describeGeometryColumn(reader.getFileSchema());
            printRowGroupBoundingBoxes(rowGroups, geometry.columnIndex());
            filterByBoundingBox(reader, totalRows);
        }
    }

    /// Find the geometry column by its logical type and describe it. GEOMETRY and GEOGRAPHY are
    /// distinct logical types — planar vs. geodesic edges — so we match both and report which one
    /// it is, along with its coordinate reference system (CRS).
    private static ColumnSchema describeGeometryColumn(FileSchema schema) {
        System.out.println("\n== Geospatial column ==");
        for (int i = 0; i < schema.getColumnCount(); i++) {
            ColumnSchema column = schema.getColumn(i);
            LogicalType logicalType = column.logicalType();
            if (logicalType instanceof LogicalType.GeometryType(String crs)) {
                System.out.printf("  %s : GEOMETRY (planar)   CRS: %s%n", column.name(), crs(crs));
                System.out.println("  values are WKB-encoded; Hardwood returns the raw bytes, JTS decodes them");
                return column;
            }
            if (logicalType instanceof LogicalType.GeographyType(
                    String crs, LogicalType.EdgeInterpolationAlgorithm edgeInterpolation
            )) {
                System.out.printf("  %s : GEOGRAPHY (geodesic, edges: %s)   CRS: %s%n",
                        column.name(), edgeInterpolation, crs(crs));
                return column;
            }
        }
        throw new IllegalStateException("no GEOMETRY/GEOGRAPHY column in " + RESOURCE);
    }

    /// Print each row group's bounding box — the index spatial pushdown consults. A box is the
    /// `[xmin, xmax] x [ymin, ymax]` extent of every geometry in that group; `intersects` keeps a
    /// group only if its box overlaps the query box, so these extents decide what gets skipped.
    private static void printRowGroupBoundingBoxes(List<RowGroup> rowGroups, int geometryColumn) {
        System.out.println("\n== Per-row-group bounding boxes (the pushdown index) ==");
        for (int i = 0; i < rowGroups.size(); i++) {
            RowGroup rowGroup = rowGroups.get(i);
            GeospatialStatistics stats = rowGroup.columns().get(geometryColumn).metaData().geospatialStatistics();
            if (stats == null || stats.bbox() == null) {
                System.out.printf("  row group %d: (no bounding box)%n", i);
                continue;
            }
            BoundingBox box = stats.bbox();
            System.out.printf("  row group %d: lon [%7.2f, %7.2f]  lat [%6.2f, %6.2f]  (%d cities)%n",
                    i, box.xmin(), box.xmax(), box.ymin(), box.ymax(), rowGroup.numRows());
        }
    }

    /// Push a bounding-box filter down with `FilterPredicate.intersects(...)` and read the matches.
    /// The reader skips row groups whose box misses Europe; for the rows it does return, we decode
    /// the WKB point with JTS to print its coordinates. Matching fewer than all rows is the visible
    /// proof that whole row groups were skipped before any geometry was decoded.
    private static void filterByBoundingBox(ParquetFileReader reader, long totalRows) throws Exception {
        FilterPredicate europe =
                FilterPredicate.intersects("location", EUROPE_XMIN, EUROPE_YMIN, EUROPE_XMAX, EUROPE_YMAX);

        System.out.printf("%n== Cities intersecting Europe [lon %.0f..%.0f, lat %.0f..%.0f] ==%n",
                EUROPE_XMIN, EUROPE_XMAX, EUROPE_YMIN, EUROPE_YMAX);

        long matched = 0;
        WKBReader wkb = new WKBReader();
        try (RowReader rows = reader.buildRowReader().filter(europe).build()) {
            while (rows.hasNext()) {
                rows.next();
                Geometry geometry = wkb.read(rows.getBinary("location"));
                Point point = (Point) geometry;
                System.out.printf("  %-10s (lon %6.2f, lat %5.2f)%n",
                        rows.getString("city_name"), point.getX(), point.getY());
                matched++;
            }
        }
        System.out.printf("  %d of %,d rows matched — the disjoint row groups were skipped by bbox%n",
                matched, totalRows);
    }

    /// An absent CRS means the OGC:CRS84 default (WGS 84 lon/lat); files may also state it explicitly.
    private static String crs(String crs) {
        return crs == null ? "OGC:CRS84 (default)" : crs;
    }

    /// Load a committed classpath fixture into memory. `InputFile.of(ByteBuffer)` reads straight from
    /// the bytes, so the example needs no files on disk and runs fully offline.
    private static ByteBuffer loadResource(String resource) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the classpath");
            }
            return ByteBuffer.wrap(in.readAllBytes());
        }
    }
}
