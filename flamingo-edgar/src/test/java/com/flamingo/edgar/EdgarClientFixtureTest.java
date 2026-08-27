package com.flamingo.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline contract tests against an embedded local HTTP server: recorded-fixture
 * discipline, no live network (CI touches EDGAR only via tagged smoke jobs).
 * Proves: UA mandate, raw-first persistence BEFORE parse, retry/backoff path,
 * terminal 4xx, and per-fetch immutability of stored raws (R1/R8).
 */
class EdgarClientFixtureTest {

    static final String SUBMISSIONS_PATH = "/submissions/CIK0000000000.json";

    HttpServer server;
    AtomicInteger hits;
    /** Deterministic per-hit script: response codes popped in order (default 200). */
    ConcurrentLinkedQueue<Integer> statuses;
    ConcurrentLinkedQueue<byte[]> bodies;
    String seenUserAgent = null;

    @TempDir
    Path tmp;

    @BeforeEach
    void startStub() throws IOException {
        hits = new AtomicInteger();
        statuses = new ConcurrentLinkedQueue<>();
        bodies = new ConcurrentLinkedQueue<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(SUBMISSIONS_PATH, exchange -> {
            seenUserAgent = exchange.getRequestHeaders().getFirst("User-Agent");
            hits.incrementAndGet();
            Integer scripted = statuses.poll();
            int code = scripted == null ? 200 : scripted;
            byte[] body = bodies.poll();
            if (body == null) {
                body = "{\"cik\":0}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    EdgarConfig cfg(double rps) {
        return new EdgarConfig("Flamingo Research <ops@flamingo.example>", tmp, rps, 3,
                java.time.Duration.ofMillis(20), baseUrl());
    }

    @Test
    void persistsBeforeParseAndSendsMandatedUserAgent() throws Exception {
        byte[] fixture = "{\"cik\":0,\"filings\":{\"recent\":{\"form\":[\"10-K\"]}}}"
                .getBytes(StandardCharsets.UTF_8);
        statuses.add(200);
        bodies.add(fixture);
        try (EdgarClient c = new EdgarClient(cfg(5))) {
            JsonNode n = c.submissions("0000000000");

            assertThat(seenUserAgent)
                    .as("§6: undeclared UAs get 403 — the header must ride every request")
                    .isEqualTo("Flamingo Research <ops@flamingo.example>");
            assertThat(n.has("filings")).isTrue();

            List<String> storedList = listStored();
            assertThat(storedList).hasSize(1);
            assertThat(c.rawStore().read(storedList.get(0)))
                    .isEqualTo(fixture); // parse consumed exactly what was persisted
        }
    }

    @Test
    void submissionsStoredBindsDocToPersistedBytes() throws Exception {
        byte[] fixture = "{\"cik\":0,\"filings\":{\"recent\":{\"form\":[\"10-K\"]}}}"
                .getBytes(StandardCharsets.UTF_8);
        statuses.add(200);
        bodies.add(fixture);
        try (EdgarClient c = new EdgarClient(cfg(5))) {
            var f = c.submissionsStored("0000000000");

            assertThat(f.doc().has("filings")).isTrue();
            assertThat(f.stored().sha256()).isEqualTo(RawStore.sha256Hex(fixture));
            // bytes on disk under the returned key are exactly what was parsed
            assertThat(c.rawStore().read(f.stored().objectKey())).isEqualTo(fixture);
        }
    }

    @Test
    void retriesTransientServiceErrorThenSucceeds() throws Exception {
        statuses.add(503); // first attempt fails transiently
        statuses.add(200); // retry succeeds
        bodies.add(new byte[0]);
        bodies.add("{\"cik\":0,\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        try (EdgarClient c = new EdgarClient(cfg(5))) {
            JsonNode n = c.fetchJson(baseUrl() + SUBMISSIONS_PATH);

            assertThat(n.get("ok").asBoolean()).isTrue();
            assertThat(hits.get()).isEqualTo(2);
            assertThat(listStored()).as("failed attempts are NOT persisted").hasSize(1);
        }
    }

    @Test
    void clientErrorIsTerminalNotRetried() throws Exception {
        statuses.add(404);
        try (EdgarClient c = new EdgarClient(cfg(5))) {
            assertThatThrownBy(() -> c.fetchJson(baseUrl() + SUBMISSIONS_PATH))
                    .isInstanceOf(EdgardHttpException.class)
                    .hasMessageContaining("404");
            assertThat(hits.get()).isEqualTo(1);
        }
    }

    @Test
    void secondFetchNeverOverwritesFirstStoredResponse() throws Exception {
        statuses.add(200);
        statuses.add(200);
        bodies.add("{\"v\":1}".getBytes(StandardCharsets.UTF_8));
        bodies.add("{\"v\":2}".getBytes(StandardCharsets.UTF_8));
        try (EdgarClient c = new EdgarClient(cfg(5))) {
            JsonNode first = c.submissions("0000000000");
            JsonNode second = c.submissions("0000000000");

            assertThat(first.get("v").asInt()).isEqualTo(1);
            assertThat(second.get("v").asInt()).isEqualTo(2);

            List<String> keys = listStored();
            assertThat(keys).hasSize(2);
            assertThat(keys.get(0)).isNotEqualTo(keys.get(1));
            assertThat(c.rawStore().read(keys.get(0))).contains("\"v\":1".getBytes()); // prior version retained
        }
    }

    private List<String> listStored() throws IOException {
        try (var walk = Files.walk(tmp)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> tmp.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
