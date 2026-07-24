package corretto.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
public interface Fetcher {
    InputStream open(String url) throws IOException;

    static Fetcher http() {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        return url -> {
            try {
                HttpResponse<InputStream> response = client.send(
                    HttpRequest.newBuilder().GET().uri(URI.create(url)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted fetching " + url, e);
            }
        };
    }
}
