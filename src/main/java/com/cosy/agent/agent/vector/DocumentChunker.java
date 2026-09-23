package com.cosy.agent.agent.vector;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切分器：长文档按段落聚合至 {@code chunkSize}，超长段落硬切，
 * 相邻块保留 {@code overlap} 字符重叠，避免语义断裂（Step 4 入库管线第一步）。
 */
@Component
public class DocumentChunker {

    public List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : text.split("\\n+")) {
            if (paragraph.isBlank()) {
                continue;
            }
            if (paragraph.length() > chunkSize) {
                flush(current, chunks, overlap);
                for (int i = 0; i < paragraph.length(); i += chunkSize - overlap) {
                    chunks.add(paragraph.substring(i, Math.min(paragraph.length(), i + chunkSize)));
                }
                continue;
            }
            if (current.length() + paragraph.length() + 1 > chunkSize && current.length() > 0) {
                flush(current, chunks, overlap);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(paragraph);
        }
        flush(current, chunks, overlap);
        return chunks;
    }

    private void flush(StringBuilder current, List<String> chunks, int overlap) {
        String chunk = current.toString().trim();
        if (chunk.isEmpty()) {
            return;
        }
        chunks.add(chunk);
        current.setLength(0);
        if (overlap > 0 && chunk.length() > overlap) {
            current.append(chunk, chunk.length() - overlap, chunk.length());
        }
    }
}
