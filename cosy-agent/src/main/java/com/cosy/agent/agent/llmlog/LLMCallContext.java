package com.cosy.agent.agent.llmlog;

/**
 * 当前模型调用的业务上下文（ThreadLocal）：由调用方（ReAct 编排/工具回调）在每次
 * 模型调用前设置、调用链结束后清理，供 {@link com.cosy.agent.agent.router.ModelRouter}
 * 埋点时关联到会话/任务/轮次。不设置时调用记录的业务关联字段为空（不阻断）。
 */
public final class LLMCallContext {

    private static final ThreadLocal<Context> CTX = new ThreadLocal<>();

    /** 业务关联信息（全可空：null 字段在记录中留空）。
     *  injectedTools：本轮注入给模型的工具清单（逗号分隔工具名），用于调用记录排查
     *  "模型是否看到某工具"；null 表示未采集（不阻断）。 */
    public record Context(String sessionId, String taskId, int iteration, String toolName,
                          String injectedTools) {
        public static final Context EMPTY = new Context(null, null, 0, null, null);
    }

    private LLMCallContext() {
    }

    public static void set(Context ctx) {
        CTX.set(ctx == null ? Context.EMPTY : ctx);
    }

    public static Context get() {
        Context ctx = CTX.get();
        return ctx == null ? Context.EMPTY : ctx;
    }

    public static void clear() {
        CTX.remove();
    }
}
