/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.examples.querycontrols;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/// Downloads the sample file this example reads so it "just runs" — no manual setup.
///
/// The file is one month of the public NYC TLC Yellow Taxi trip-record dataset, saved
/// into a local `data/` folder. If it is already there, it is reused.
public final class Datasets {

    private static final String FILE = "yellow_tripdata_2026-01.parquet";
    private static final String URL = "https://d37ci6vzurychx.cloudfront.net/trip-data/" + FILE;

    private Datasets() {
    }

    /// Local path to the Yellow Taxi file, downloading it if not already present.
    public static Path yellowTaxi() throws IOException, InterruptedException {
        Path target = Path.of("data", FILE);
        if (Files.exists(target)) {
            return target;
        }
        download(URL, target);
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
