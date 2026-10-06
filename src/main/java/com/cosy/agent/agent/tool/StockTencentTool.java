package com.cosy.agent.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 腾讯证券实时行情（非官方抓包接口）：GET https://qt.gtimg.cn/q={code}，
 * 响应为 GBK 编码的 JS 变量字符串（"~" 分隔），解析关键字段。
 * 因响应非 JSON + 需 GBK 解码，实现为本地工具。
 *
 * <p>code 格式：A股 sh600519 / sz000001；美股 usAAPL；港股 hk00700。
 * 非官方接口、无 SLA，仅作参考行情，勿用于交易决策。</p>
 */
@Component
public class StockTencentTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(StockTencentTool.class);
    private static final Charset GBK = Charset.forName("GBK");

    private final HttpClient http;

    public StockTencentTool() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "stock_quote_tencent";
    }

    @Override
    public String description() {
        return "腾讯证券实时行情（非官方免费接口，实时参考价，勿用于交易决策）。参数 code 如 sh600519/sz000001/usAAPL/hk00700，返回名称/现价/涨跌幅/最高最低/时间等。";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String code = String.valueOf(args.getOrDefault("code", "")).trim();
        if (code.isEmpty()) {
            return Map.of("error", "参数 code 必填（如 sh600519 / usAAPL / hk00700）");
        }
        try {
            String url = "https://qt.gtimg.cn/q=" + code;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return Map.of("error", "腾讯行情 HTTP " + resp.statusCode());
            }
            String text = new String(resp.body(), GBK).trim();
            int quoteStart = text.indexOf('"');
            int quoteEnd = text.lastIndexOf('"');
            if (quoteStart < 0 || quoteEnd <= quoteStart) {
                return Map.of("error", "腾讯行情响应格式异常", "raw", text);
            }
            // 腾讯字段（~ 分隔）：1名称 2代码 3现价 4昨收 5今开 30时间 31涨跌 32涨跌% 33最高 34最低
            String[] f = text.substring(quoteStart + 1, quoteEnd).split("~");
            Map<String, Object> result = new LinkedHashMap<>();
            if (f.length > 1) result.put("name", f[1]);
            if (f.length > 2) result.put("code", f[2]);
            if (f.length > 3) result.put("price", f[3]);
            if (f.length > 4) result.put("prevClose", f[4]);
            if (f.length > 5) result.put("open", f[5]);
            if (f.length > 31) result.put("change", f[31]);
            if (f.length > 32) result.put("changePercent", f[32]);
            if (f.length > 33) result.put("high", f[33]);
            if (f.length > 34) result.put("low", f[34]);
            if (f.length > 30) result.put("time", f[30]);
            if (f.length > 6) result.put("volume", f[6]);   // 成交量（手）
            if (f.length > 37) result.put("amount", f[37]); // 成交额（万元）
            result.put("source", "tencent(非官方)");
            return result;
        } catch (Exception e) {
            log.warn("腾讯行情调用失败: code={}, cause={}", code, e.getMessage());
            return Map.of("error", "腾讯行情调用失败: " + e.getMessage());
        }
    }
}
