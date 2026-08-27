package com.flamingo.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;

/**
 * SEC EDGAR client (build order §6 contract).
 *
 * <p>Hard properties, all enforced here:</p>
 * <ul>
 *   <li>Mandated User-Agent on every request (SEC 403s undeclared UAs).</li>
 *   <li>Token bucket &le;5 req/s and &le;{@value EdgarConfig#MAX_CONCURRENT_CONTRACT}
 *       concurrent requests.</li>
 *   <li>Exponential backoff + jitter on 403/503; Retry-After honored.</li>
 *   <li><b>R1:</b> every response body is persisted to the RawStore BEFORE any
 *       parsing happens; parsing consumes only the stored copy.</li>
 * </ul>
 */
public final class EdgarClient implements AutoCloseable {

    private static final String WWW_HOST = "https://www.sec.gov"; // reserved: Archives/daily-index lanes

    private final EdgarConfig cfg;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final TokenBucket bucket;
    private final Semaphore concurrency;
    private final BackoffPolicy backoff;
    private final RawStore rawStore;

    public EdgarClient(EdgarConfig cfg) {
        this.cfg = cfg;
        this.bucket = new TokenBucket(cfg.requestsPerSecond());
        this.concurrency = new Semaphore(EdgarConfig.MAX_CONCURRENT_CONTRACT);
        this.backoff = new BackoffPolicy(cfg.maxAttempts(), cfg.baseBackoff());
        this.rawStore = new RawStore(cfg.rawStorePath());
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public RawStore rawStore() {
        return rawStore;
    }

    /** Submissions feed: all recent filings metadata for a CIK. */
    public JsonNode submissions(String cik10) {
        return fetchParsed(cfg.dataBaseUrl() + "/submissions/CIK" + cik10 + ".json");
    }

    /** Parsed body + the R1-stored bytes' integrity metadata together — callers that
     *  must PROVE what they ingested bind rows to {@code stored()} keys, never guesses. */
    public record Fetched(JsonNode doc, RawStore.Stored stored) {}

    /** Submissions feed with raw-store binding: feeds ingestion provenance columns. */
    public Fetched submissionsStored(String cik10) {
        String url = cfg.dataBaseUrl() + "/submissions/CIK" + cik10 + ".json";
        RawStore.Stored s = fetchRaw(url);
        try {
            return new Fetched(mapper.readTree(s.bytes()), s);
        } catch (IOException e) {
            throw new IllegalStateException("stored response failed to parse: " + s.objectKey(), e);
        }
    }

    /** Pre-flattened XBRL facts (companyfacts API) — primary Phase-0 fact source. */
    public JsonNode companyFacts(String cik10) {
        return fetchParsed(cfg.dataBaseUrl() + "/api/xbrl/companyfacts/CIK" + cik10 + ".json");
    }

    /** Single-concept slice of companyfacts. */
    public JsonNode companyConcept(String cik10, String taxonomy, String tag) {
        return fetchParsed(cfg.dataBaseUrl() + "/api/xbrl/companyconcept/CIK" + cik10 + "/"
                + taxonomy + "/" + tag + ".json");
    }

    /** Generic GET into Archives / daily-index / browse endpoints, still R1-guarded. */
    public JsonNode fetchJson(String absoluteUrl) {
        return fetchParsed(absoluteUrl);
    }

    /**
     * EDGAR full-text search (build order §6): month-window queries against
     * {@code efts.sec.gov/LATEST/search-index}, pagination semantics deliberately
     * ignored per contract — callers union disjoint month windows instead. R1 applies.
     */
    public JsonNode searchIndex(String isoStartDate, String isoEndDate) {
        String url = "https://efts.sec.gov/LATEST/search-index?q=&forms=424B5"
                + "&startdt=" + isoStartDate + "&enddt=" + isoEndDate;
        return fetchParsed(url);
    }

    /** Filing directory index.json on www.sec.gov (resolve primary doc before fetch). */
    public JsonNode filingIndex(int cikInt, String accessionNoDash) {
        return fetchParsed("https://www.sec.gov/Archives/edgar/data/" + cikInt
                + "/" + accessionNoDash + "/index.json");
    }

    private JsonNode fetchParsed(String url) {
        RawStore.Stored stored = fetchRaw(url);
        try {
            return mapper.readTree(stored.bytes());
        } catch (IOException e) {
            // R1 satisfied: bytes are already persisted; upstream can replay from store.
            throw new IllegalStateException("stored response failed to parse: " + stored.objectKey(), e);
        }
    }

    /** Executes the full rate-limited/backoff GET; persists body before returning it. */
    @SuppressWarnings("unchecked")
    public RawStore.Stored fetchRaw(String url) {
        URI uri = URI.create(url);
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", cfg.userAgent())
                // NOTE: deliberately NO Accept-Encoding here — java.net.http does not
                // auto-decompress, and BodyHandlers.ofByteArray would persist gzipped
                // bytes our parsers cannot read. Identity encoding only.
                .GET();
        int attempt = 0;
        while (true) {
            attempt++;
            bucket.acquire();
            try {
                concurrency.acquire();
                HttpResponse<byte[]> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() == 200) {
                    RawStore.Stored stored = rawStore.put(url, resp.body());
                    // cast trick avoided: return typed properly below
                    return stored;
                }
                if (!backoff.shouldRetry(resp) || backoff.exhausted(attempt)) {
                    throw new EdgardHttpException(url, resp.statusCode(),
                            "no Retry-After honored, attempts=" + attempt);
                }
                backoff.waitBeforeRetry(attempt, resp);
            } catch (IOException e) {
                if (attempt >= cfg.maxAttempts()) {
                    throw new IllegalStateException("transport failure after " + attempt + " attempts: " + url, e);
                }
                try {
                    Thread.sleep(backoff.delayFor(attempt, null).toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted between transport retries", ie);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted awaiting request slot", e);
            } finally {
                concurrency.release();
            }
        }
    }

    @Override
    public void close() {
        // HttpClient has no explicit close pre-Java 21; kept for symmetric resource use.
    }
}
