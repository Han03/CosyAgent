package com.cosy.agent.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储契约：按层级读写会话 / 用户记忆。
 */
public interface MemoryStore {

    void save(MemoryRecord record);

    Optional<String> load(MemoryLevel level, String sessionId, String key);

    void delete(MemoryLevel level, String sessionId, String key);

    List<MemoryRecord> list(MemoryLevel level, String sessionId);

    /** 按内容语义检索（Step 3 起基于关键词 / 向量化实现） */
    List<MemoryRecord> search(MemoryLevel level, String sessionId, String query, int topK);
}
