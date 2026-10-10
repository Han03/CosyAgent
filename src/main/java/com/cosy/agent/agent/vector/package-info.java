/**
 * 向量检索层：PGVector 知识库 RAG。
 *
 * <p><b>包内阅读顺序</b></p>
 * <ol>
 *   <li>{@link com.cosy.agent.agent.vector.Vectorizer} —— 向量化接口</li>
 *   <li>{@link com.cosy.agent.agent.vector.DeterministicVectorizer} —— 确定性哈希向量化
 *       （无外部 Embedding 依赖，CI 可复现；生产可替换为真实 Embedding 模型）</li>
 *   <li>{@link com.cosy.agent.agent.vector.DocumentChunker} —— 文档切块（chunk + overlap）</li>
 *   <li>{@link com.cosy.agent.agent.vector.VectorKnowledgeStore} —— 存储接口（upsert / search）</li>
 *   <li>{@link com.cosy.agent.agent.vector.InMemoryKnowledgeStore} / {@link com.cosy.agent.agent.vector.PgVectorKnowledgeStore}
 *       —— 内存 / PostgreSQL pgvector 实现</li>
 * </ol>
 *
 * <p><b>被依赖</b>：agent.core.DefaultReActAgent 知识增强时检索；
 * controller.KnowledgeController 负责写入。容错走 ResilienceTarget.VECTOR。</p>
 */
package com.cosy.agent.agent.vector;
