package com.cosy.agent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 百度翻译·标准版（免费：不限量、永久免费）：GET /api/trans/vip/translate，
 * 鉴权为 MD5 动态签名（sign = MD5(appid + q + salt + secret)），无法用能力注册中心
 * 的静态认证表达，故实现为本地工具。
 *
 * <p>配置（环境变量或 application.yml）：cosy.agent.tool.baidu-translate.appid / .secret，
 * 未配置时执行返回明确提示（不抛异常，供模型向用户索要配置）。</p>
 */
@Component
public class BaiduTranslateTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(BaiduTranslateTool.class);
    private static final String ENDPOINT = "https://api.fanyi.baidu.com/api/trans/vip/translate";

    private final HttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cosy.agent.tool.baidu-translate.appid:}")
    private String appid;

    @Value("${cosy.agent.tool.baidu-translate.secret:}")
    private String secret;

    public BaiduTranslateTool() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String name() {
        return "baidu_translate";
    }

    @Override
    public String description() {
        return "中英互译（百度翻译标准版，免费不限量）。参数 q 为待翻译文本，from/to 语言代码（auto/zh/en），返回译文。";
    }

    @Override
    public boolean retryable() {
        return true;
    }

    @Override
    public Object execute(Map<String, Object> args) {
        if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
            return Map.of("error", "百度翻译未配置 appid/secret（cosy.agent.tool.baidu-translate.appid/.secret）");
        }
        String q = String.valueOf(args.getOrDefault("q", "")).trim();
        if (q.isEmpty()) {
            return Map.of("error", "参数 q（待翻译文本）必填");
        }
        String from = String.valueOf(args.getOrDefault("from", "auto")).trim();
        String to = String.valueOf(args.getOrDefault("to", "zh")).trim();
        String salt = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 999999));
        String sign = md5(appid + q + salt + secret);
        try {
            String url = ENDPOINT
                    + "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&from=" + URLEncoder.encode(from, StandardCharsets.UTF_8)
                    + "&to=" + URLEncoder.encode(to, StandardCharsets.UTF_8)
                    + "&appid=" + URLEncoder.encode(appid, StandardCharsets.UTF_8)
                    + "&salt=" + salt
                    + "&sign=" + sign;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return Map.of("error", "百度翻译 HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = objectMapper.readTree(resp.body());
            if (root.has("error_code") && !"0".equals(root.get("error_code").asText())) {
                return Map.of("error", "百度翻译错误 " + root.get("error_code").asText() + ": " + root.get("error_msg").asText());
            }
            Map<String, Object> result = new HashMap<>();
            result.put("query", q);
            result.put("from", root.path("from").asText());
            result.put("to", root.path("to").asText());
            StringBuilder translated = new StringBuilder();
            for (JsonNode item : root.path("trans_result")) {
                if (!translated.isEmpty()) {
                    translated.append('\n');
                }
                translated.append(item.path("dst").asText());
            }
            result.put("translation", translated.toString());
            return result;
        } catch (Exception e) {
            log.warn("百度翻译调用失败: {}", e.getMessage());
            return Map.of("error", "百度翻译调用失败: " + e.getMessage());
        }
    }

    private static String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }
}
