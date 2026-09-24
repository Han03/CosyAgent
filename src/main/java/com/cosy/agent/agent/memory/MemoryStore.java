package com.cosy.agent.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * 记忆存储契约：按层级读写会话 / 用户记忆。
 *
 * <p>命名空间约定：会话级记忆（WORKING / SESSION）传 sessionId；
 * 长期记忆（LONG_TERM）传 userId，实现跨会话画像复用。</p>
 */
public interface MemoryStore {

    void save(MemoryRecord record);

    Optional<String> load(MemoryLevel level, String namespace, String key);

    void delete(MemoryLevel level, String namespace, String key);

    /** 删除整个命名空间（会话删除联动清理：cosy:work|session:{namespace}:*） */
    void deleteNamespace(MemoryLevel level, String namespace);

    List<MemoryRecord> list(MemoryLevel level, String namespace);

    /** 按内容检索（Step 3 关键词过滤，Step 4 升级为向量化语义检索） */
    List<MemoryRecord> search(MemoryLevel level, String namespace, String query, int topK);
}
