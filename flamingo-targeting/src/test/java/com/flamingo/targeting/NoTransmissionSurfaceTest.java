package com.flamingo.targeting;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §11 architecture-audit question, answered as a BUILD GATE:
 * "could this system transmit anything to anyone if the operator wished?"
 * Answer NO at code level — this test fails the build if any transport surface
 * (HTTP client, socket, mail, webhook) enters the targeting module's classpath
 * or source tree.
 */
class NoTransmissionSurfaceTest {

    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "java.net.http", "java.net.Socket", "java.net.URL openConnection",
            "HttpClient", "OkHttp", "RestTemplate", "WebClient",
            "javax.mail", "jakarta.mail", "smtp", "SendGrid", "SES",
            "webhook", "KafkaTemplate", "RabbitTemplate", "JmsTemplate");

    @Test
    void moduleSourceContainsNoTransportSurfaces() throws IOException {
        // Scan PRODUCTION code only (src/main). The test tree necessarily contains
        // the forbidden marker strings — that's how this guard is expressed.
        Path src = Paths.get("..", "flamingo-targeting", "src", "main");
        assertThat(src).as("targeting module main source tree").exists();
        try (Stream<Path> walk = Files.walk(src)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String content;
                try {
                    content = Files.readString(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                for (String marker : FORBIDDEN_MARKERS) {
                    assertThat(content)
                            .as("%s must not contain transport marker '%s' (§11 no-transmission guarantee)",
                                    p.getFileName(), marker)
                            .doesNotContain(marker);
                }
            });
        }
    }

    @Test
    void modulePomAddsNoTransportDependencies() throws IOException {
        String pom = Files.readString(Paths.get("..", "flamingo-targeting", "pom.xml"));
        for (String forbidden : List.of("httpclient", "okhttp", "webflux", "mail",
                "spring-boot-starter-web", "kafka", "amqp", "netty")) {
            assertThat(pom.toLowerCase())
                    .as("targeting pom must not declare '%s'", forbidden)
                    .doesNotContain(forbidden);
        }
    }
}
