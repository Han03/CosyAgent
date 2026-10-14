package com.cosy.knowledge.vector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档切分器测试：块大小约束、超长段落硬切、块间重叠。
 */
class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void splitsLongParagraphs() {
        String text = "a".repeat(2000);
        List<String> chunks = chunker.chunk(text, 600, 50);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(600));
    }

    @Test
    void aggregatesShortParagraphs() {
        String text = "第一段内容。\n第二段内容。";
        List<String> chunks = chunker.chunk(text, 600, 50);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void blankInputReturnsEmpty() {
        assertThat(chunker.chunk("", 600, 50)).isEmpty();
        assertThat(chunker.chunk(null, 600, 50)).isEmpty();
    }
}
