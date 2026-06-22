package com.stockpulse.notification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageSplitterTest {

    @Test
    void keepsShortBodyAsSingleChunk() {
        List<String> chunks = MessageSplitter.split("hello\nworld", 100);
        assertThat(chunks).containsExactly("hello\nworld");
    }

    @Test
    void splitsOnLineBoundaries() {
        String body = "aaaa\nbbbb\ncccc"; // each line 4 chars
        List<String> chunks = MessageSplitter.split(body, 9); // "aaaa\nbbbb" = 9
        assertThat(chunks).containsExactly("aaaa\nbbbb", "cccc");
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(9));
    }

    @Test
    void hardSplitsAnOverlongLine() {
        String body = "x".repeat(25);
        List<String> chunks = MessageSplitter.split(body, 10);
        assertThat(chunks).containsExactly("xxxxxxxxxx", "xxxxxxxxxx", "xxxxx");
    }

    @Test
    void handlesEmptyBody() {
        assertThat(MessageSplitter.split("", 10)).containsExactly("");
        assertThat(MessageSplitter.split(null, 10)).containsExactly("");
    }
}
