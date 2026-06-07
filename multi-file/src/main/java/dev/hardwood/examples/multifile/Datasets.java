/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.multifile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Downloads the sample files this example reads so it "just runs" — no manual setup.
///
/// These are three consecutive months (January–March 2026) of the public NYC TLC Yellow Taxi
/// trip-record dataset, saved into a local `data/` folder. Files already present are reused.
public final class Datasets {

    /// The months this example reads as one logical dataset, in order.
    private static final List<String> MONTHS = List.of("2026-01", "2026-02", "2026-03");

    private static final String BASE_URL = "https://d37ci6vzurychx.cloudfront.net/trip-data/";

    private Datasets() {
    }

    /// Local paths to the three monthly Yellow Taxi files, downloading any that are missing.
    ///
    /// The returned list is in month order, so the caller can label each file by index.
    public static List<Path> yellowTaxiMonths() throws IOException, InterruptedException {
        List<Path> paths = new ArrayList<>(MONTHS.size());
        for (String month : MONTHS) {
            paths.add(yellowTaxi(month));
        }
        return paths;
    }

    private static Path yellowTaxi(String month) throws IOException, InterruptedException {
        String file = "yellow_tripdata_" + month + ".parquet";
        Path target = Path.of("data", file);
        if (Files.exists(target)) {
            return target;
        }
        download(BASE_URL + file, target);
        return target;
    }

    private static void download(String url, Path target) throws IOException, InterruptedException {
        System.out.println("Downloading " + url);
        Files.createDirectories(target.getParent());
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new IOException("Download failed (HTTP " + response.statusCode() + ") for " + url);
        }
        System.out.println("  saved to " + target);
    }
}
