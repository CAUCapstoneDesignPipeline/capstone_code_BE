import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class Healthcheck {
    public static void main(String[] args) {
        try {
            var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080/actuator/health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            System.exit(response.statusCode() == 200 && response.body().contains("\"UP\"") ? 0 : 1);
        } catch (Exception ignored) { System.exit(1); }
    }
}
