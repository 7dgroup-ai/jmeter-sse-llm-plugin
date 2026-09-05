package com.llm.perf.sse;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * SSE (Server-Sent Events) 流式 LLM API 采样器。
 *
 * <p>用于对流式 LLM API（如 OpenAI Chat Completions Stream）进行性能测试。
 * 支持 SSE 协议解析，自动计算以下性能指标：</p>
 * <ul>
 *   <li>TTFT (Time To First Token) - 首个 token 延迟</li>
 *   <li>TTFB (Time To First Byte) - 首字节延迟</li>
 *   <li>TPOT (Time Per Output Token) - 每个输出 token 耗时</li>
 *   <li>Token/s - 吞吐率</li>
 *   <li>TotalRT (Total Response Time) - 总响应时间</li>
 * </ul>
 *
 * @author liwen
 * Date  2025-09-03
 */
public class SseStreamSampler extends AbstractSampler {
    /** URL 配置属性键 */
    public static final String URL = "SseStreamSampler.url";
    /** 请求体配置属性键 */
    public static final String REQUEST_BODY = "SseStreamSampler.body";
    /** 请求头配置属性键，格式：HeaderName:HeaderValue，每行一个 */
    public static final String HEADERS = "SseStreamSampler.headers";
    /** 连接超时时间（毫秒），默认 10000 */
    public static final String CONNECT_TIMEOUT = "SseStreamSampler.connectTimeout";
    /** 读取超时时间（毫秒），默认 60000 */
    public static final String READ_TIMEOUT = "SseStreamSampler.readTimeout";

    /** HTTP 连接池管理器，支持并发请求 */
    private static final PoolingHttpClientConnectionManager CONNECTION_MANAGER = new PoolingHttpClientConnectionManager();
    /** 共享的 HTTP 客户端实例 */
    private static final CloseableHttpClient HTTP_CLIENT;
    /** 线程安全的 Gson 实例，避免多线程竞争 */
    private static final ThreadLocal<Gson> GSON_LOCAL = ThreadLocal.withInitial(Gson::new);

    static {
        // 配置连接池：最大 200 个连接，每条路由最多 50 个连接
        CONNECTION_MANAGER.setMaxTotal(200);
        CONNECTION_MANAGER.setDefaultMaxPerRoute(50);
        HTTP_CLIENT = HttpClients.custom()
                .setConnectionManager(CONNECTION_MANAGER)
                .evictIdleConnections(60, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    /**
     * 执行一次 SSE 流式请求采样。
     *
     * <p>核心流程：</p>
     * <ol>
     *   <li>记录请求开始时间</li>
     *   <li>发起 HTTP POST 请求，接收 SSE 流响应</li>
     *   <li>逐行读取流数据，解析 SSE 协议</li>
     *   <li>提取 token 内容，计算各项性能指标</li>
     *   <li>将指标序列化为 JSON 存入 SampleResult</li>
     * </ol>
     *
     * @param entry JMeter 入口点（通常为 null）
     * @return 包含性能指标的 SampleResult
     */
    @Override
    public SampleResult sample(Entry entry) {
        SseMetrics metrics = new SseMetrics();
        metrics.requestStartTime = System.currentTimeMillis();

        SampleResult mainResult = new SampleResult();
        mainResult.setSampleLabel(getName());

        String urlStr = getPropertyAsString(URL);
        String body = getPropertyAsString(REQUEST_BODY);
        int connectTimeout = getPropertyAsInt(CONNECT_TIMEOUT, 10000);
        int readTimeout = getPropertyAsInt(READ_TIMEOUT, 60000);

        // 参数校验
        if (urlStr == null || urlStr.trim().isEmpty()) {
            mainResult.setSuccessful(false);
            mainResult.setResponseMessage("URL不能为空");
            return mainResult;
        }
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            mainResult.setSuccessful(false);
            mainResult.setResponseMessage("URL必须以http://或https://开头");
            return mainResult;
        }
        if (body == null || body.trim().isEmpty()) {
            mainResult.setSuccessful(false);
            mainResult.setResponseMessage("请求体不能为空");
            return mainResult;
        }

        // 构建 HTTP POST 请求
        HttpPost httpPost = new HttpPost(urlStr);
        httpPost.setConfig(RequestConfig.custom()
                .setConnectTimeout(connectTimeout)
                .setSocketTimeout(readTimeout)
                .setConnectionRequestTimeout(connectTimeout)
                .build());

        // 解析自定义请求头（格式：HeaderName:HeaderValue，每行一个）
        String headerStr = getPropertyAsString(HEADERS);
        if (headerStr != null && !headerStr.isEmpty()) {
            String[] lines = headerStr.split("\n");
            for (String h : lines) {
                String[] kv = h.split(":", 2);
                if (kv.length == 2) {
                    httpPost.setHeader(kv[0].trim(), kv[1].trim());
                }
            }
        }

        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

        BufferedReader reader = null;
        boolean success = true;
        String responseMsg = "";

        try {
            HttpResponse response = HTTP_CLIENT.execute(httpPost);
            int code = response.getStatusLine().getStatusCode();
            if (code >= 400) {
                // HTTP 错误
                success = false;
                responseMsg = "HTTP error code:" + code;
            } else {
                // 成功响应，开始解析 SSE 流
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    reader = new BufferedReader(new InputStreamReader(entity.getContent(), StandardCharsets.UTF_8));
                    String line;
                    boolean firstByte = true;

                    // 逐行读取 SSE 流数据
                    while ((line = reader.readLine()) != null) {
                        long now = System.currentTimeMillis();

                        // 记录首字节时间（TTFB）
                        if (firstByte) {
                            metrics.firstByteTime = now;
                            firstByte = false;
                        }

                        // 解析 SSE 事件
                        SseEvent evt = SseEvent.parse(line);
                        if (evt.isDone()) break;  // 遇到 [DONE] 信号，结束流

                        // 获取 JSON payload，跳过非 data 行
                        JsonObject json = evt.getJsonPayload();
                        if (json == null) continue;

                        // 处理 delta.content（token 内容）
                        processDeltaContent(json, now, metrics);
                        // 处理 usage 信息（token 统计）
                        processUsage(json, metrics);
                    }
                }
            }
        } catch (Exception e) {
            success = false;
            responseMsg = e.getMessage();
        } finally {
            closeQuietly(reader);
        }

        metrics.requestEndTime = System.currentTimeMillis();

        mainResult.setSuccessful(success);
        mainResult.setResponseMessage(responseMsg);

        // 将指标 JSON 写入 responseData（供 Listener 和 BackendListener 读取）
        String metricsJson = GSON_LOCAL.get().toJson(metrics);
        mainResult.setResponseData(metricsJson, StandardCharsets.UTF_8.name());

        // 将可读文本格式写入 samplerData（供调试查看）
        StringBuilder sb = new StringBuilder();
        sb.append("TTFT(ms):").append(metrics.getTTFT()).append("\n");
        sb.append("TTFB(ms):").append(metrics.getTTFB()).append("\n");
        sb.append("TPOT(ms/token):").append(String.format("%.2f", metrics.getTPOT())).append("\n");
        sb.append("Token/s:").append(String.format("%.2f", metrics.getTokenPerSec())).append("\n");
        sb.append("TotalRT(ms):").append(metrics.getTotalRT()).append("\n");
        sb.append("inputTokens:").append(metrics.inputTokens).append("\n");
        sb.append("outputTokens:").append(metrics.outputTokens).append("\n");
        sb.append("tokenCount:").append(metrics.tokenCount).append("\n");
        mainResult.setSamplerData(sb.toString());

        return mainResult;
    }

    /**
     * 处理 SSE 事件中的 delta.content 字段。
     *
     * <p>OpenAI 格式的 SSE 响应结构：</p>
     * <pre>
     * data: {"choices":[{"delta":{"content":"Hello"}}]}
     * </pre>
     *
     * @param json SSE 事件的 JSON payload
     * @param now 当前时间戳（毫秒）
     * @param metrics 指标收集器
     */
    private void processDeltaContent(JsonObject json, long now, SseMetrics metrics) {
        if (!json.has("choices") || json.get("choices").isJsonNull()) return;
        JsonArray choicesArr = json.getAsJsonArray("choices");
        if (choicesArr == null || choicesArr.size() == 0) return;

        JsonObject choice = choicesArr.get(0).getAsJsonObject();
        if (!choice.has("delta")) return;

        JsonObject delta = choice.getAsJsonObject("delta");
        if (!delta.has("content") || delta.get("content").isJsonNull()) return;

        String content = delta.get("content").getAsString();
        if (content != null && !content.isEmpty()) {
            metrics.tokenCount++;
            metrics.lastTokenTime = now;
            // 首次收到有效 content 时记录 TTFT
            if (metrics.firstTokenTime == -1) {
                metrics.firstTokenTime = now;
            }
        }
    }

    /**
     * 处理 SSE 事件中的 usage 信息。
     *
     * <p>OpenAI 格式的 usage 结构：</p>
     * <pre>
     * data: {"usage":{"prompt_tokens":10,"completion_tokens":20}}
     * </pre>
     *
     * @param json SSE 事件的 JSON payload
     * @param metrics 指标收集器
     */
    private void processUsage(JsonObject json, SseMetrics metrics) {
        if (!json.has("usage") || json.get("usage").isJsonNull()) return;
        JsonObject usage = json.getAsJsonObject("usage");
        if (usage == null) return;

        if (usage.has("prompt_tokens")) {
            metrics.inputTokens = usage.get("prompt_tokens").getAsLong();
        }
        if (usage.has("completion_tokens")) {
            metrics.outputTokens = usage.get("completion_tokens").getAsLong();
        }
    }

    /**
     * 静默关闭 BufferedReader。
     *
     * @param reader 要关闭的 reader，允许为 null
     */
    private void closeQuietly(BufferedReader reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
    }
}
