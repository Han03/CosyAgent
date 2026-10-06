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
 * 新浪财经实时行情（非官方抓包接口）：GET https://hq.sinajs.cn/list={code}，
 * 需携带 Referer 头（否则 403）；响应为 GBK 编码的 JS 变量字符串，按逗号 split 解析。
 * 因响应非 JSON + 需自定义解码，实现为本地工具（能力注册中心无法表达）。
 *
 * <p>code 格式：A股 sh600519 / sz000001；美股 gb_aapl；港股 hk00700。
 * 非官方接口、无 SLA，仅作参考行情，勿用于交易决策；解析异常兜底返回原文。</p>
 */
@Component
public class StockSinaTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(StockSinaTool.class);
    private static final Charset GBK = Charset.forName("GBK");

    private final HttpClient http;

    public StockSinaTool() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "stock_quote_sina";
    }

    @Override
    public String description() {
        return "新浪财经实时行情（非官方免费接口，实时参考价，勿用于交易决策）。参数 code 如 sh600519/sz000001/gb_aapl/hk00700，返回名称/现价/涨跌/最高最低等。";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        String code = String.valueOf(args.getOrDefault("code", "")).trim();
        if (code.isEmpty()) {
            return Map.of("error", "参数 code 必填（如 sh600519 / gb_aapl / hk00700）");
        }
        try {
            String url = "https://hq.sinajs.cn/list=" + code;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("Referer", "https://finance.sina.com.cn")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return Map.of("error", "新浪行情 HTTP " + resp.statusCode());
            }
            String text = new String(resp.body(), GBK).trim();
            int quoteStart = text.indexOf('"');
            int quoteEnd = text.lastIndexOf('"');
            if (quoteStart < 0 || quoteEnd <= quoteStart) {
                return Map.of("error", "新浪行情响应格式异常", "raw", text);
            }
            String[] f = text.substring(quoteStart + 1, quoteEnd).split(",");
            Map<String, Object> result = new LinkedHashMap<>();
            if (code.startsWith("gb_") || code.startsWith("sh") || code.startsWith("sz")) {
                // A股/美股字段顺序一致：0名称 1今开 2昨收 3现价 4最高 5最低
                if (f.length > 0) result.put("name", f[0]);
                if (f.length > 3) result.put("price", parse(f[3]));
                if (f.length > 2) result.put("prevClose", parse(f[2]));
                if (f.length > 1) result.put("open", parse(f[1]));
                if (f.length > 4) result.put("high", parse(f[4]));
                if (f.length > 5) result.put("low", parse(f[5]));
                if (f.length > 8) {
                    result.put("volume", f[8]);       // 成交量（股）
                    result.put("amount", f[9]);       // 成交额（元）
                }
                if (f.length > 3 && f.length > 2) {
                    double price = parseDouble(f[3]), prev = parseDouble(f[2]);
                    if (prev != 0) {
                        result.put("change", round(price - prev));
                        result.put("changePercent", round((price - prev) / prev * 100));
                    }
                }
                result.put("market", code.startsWith("gb_") ? "US" : "CN");
            } else {
                // 港股：0名称 1代码 2今开 3昨收 4最高 5最低 6现价
                if (f.length > 0) result.put("name", f[0]);
                if (f.length > 6) result.put("price", parse(f[6]));
                if (f.length > 3) result.put("prevClose", parse(f[3]));
                if (f.length > 2) result.put("open", parse(f[2]));
                if (f.length > 4) result.put("high", parse(f[4]));
                if (f.length > 5) result.put("low", parse(f[5]));
                result.put("market", "HK");
            }
            result.put("source", "sina(非官方)");
            return result;
        } catch (Exception e) {
            log.warn("新浪行情调用失败: code={}, cause={}", code, e.getMessage());
            return Map.of("error", "新浪行情调用失败: " + e.getMessage());
        }
    }

    private static String parse(String s) {
        try {
            double d = Double.parseDouble(s);
            return String.valueOf(round(d));
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double d) {
        return Math.round(d * 100) / 100.0;
    }
}
