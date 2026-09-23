package com.cosy.agent.agent.vector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentChunkerTest {

    private final DocumentChunker chunker = new DocumentChunker();

    @Test
    void chunksShortDocumentIntoSingleChunk() {
        List<String> chunks = chunker.chunk("你好，这是简短文档。", 600, 50);
        assertThat(chunks).containsExactly("你好，这是简短文档。");
    }

    @Test
    void chunksLongDocumentIntoMultipleChunks() {
        StringBuilder doc = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            doc.append("第").append(i).append("段：企业知识库内容填充，用于验证切分逻辑是否按块大小正确拆分。\n");
        }
        List<String> chunks = chunker.chunk(doc.toString(), 100, 10);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allMatch(c -> c.length() <= 100);
    }

    @Test
    void keepsOverlapBetweenChunks() {
        StringBuilder doc = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            doc.append("序号").append(i).append("：重复内容用于触发切分与重叠保留。\n");
        }
        List<String> chunks = chunker.chunk(doc.toString(), 100, 20);
        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String curr = chunks.get(i);
            assertThat(prev.substring(Math.max(0, prev.length() - 20))).isEqualTo(curr.substring(0, Math.min(20, curr.length())));
        }
    }

    @Test
    void ignoresBlankInput() {
        assertThat(chunker.chunk("   ", 100, 10)).isEmpty();
        assertThat(chunker.chunk(null, 100, 10)).isEmpty();
    }
}
