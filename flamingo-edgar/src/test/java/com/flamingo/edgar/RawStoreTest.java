package com.flamingo.edgar;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RawStoreTest {

    @TempDir
    Path tmp;

    @Test
    void storesUnderSourceUrlHashTimestampLayout() throws Exception {
        RawStore store = new RawStore(tmp);
        String url = "https://data.sec.gov/submissions/CIK0000320193.json";
        byte[] body = "{\"cik\":320193}".getBytes();
        Instant t = Instant.parse("2026-08-27T02:15:00Z");

        RawStore.Stored s = store.put(url, body, t);

        String expectedSha = RawStore.sha256Hex(body);
        String urlHash = RawStore.sha256Hex(url.getBytes());
        assertThat(s.objectKey()).isEqualTo(
                "data.sec.gov/" + urlHash + "/20260827T021500Z.bin");
        assertThat(s.sha256()).isEqualTo(expectedSha);

        Path resolved = tmp.resolve(s.objectKey());
        assertThat(resolved).exists();
        assertThat(Files.readAllBytes(resolved)).isEqualTo(body);
    }

    @Test
    void sameUrlSameSecondDoesNotOverwrite() {
        RawStore store = new RawStore(tmp);
        String url = "https://www.sec.gov/cgi-bin/browse-edgar?action=company";
        byte[] b1 = "first".getBytes();
        byte[] b2 = "second".getBytes();
        Instant t = Instant.parse("2026-08-27T02:15:00Z");

        RawStore.Stored s1 = store.put(url, b1, t);
        RawStore.Stored s2 = store.put(url, b2, t);

        assertThat(s2.objectKey()).isNotEqualTo(s1.objectKey());
        assertThat(store.read(s1.objectKey())).isEqualTo(b1); // history intact (R8 spirit)
        assertThat(store.read(s2.objectKey())).isEqualTo(b2);
        assertThat(s1.sha256()).isNotEqualTo(s2.sha256());
    }

    @Test
    void sha256MatchesKnownVector() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertThat(RawStore.sha256Hex("abc".getBytes()))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
