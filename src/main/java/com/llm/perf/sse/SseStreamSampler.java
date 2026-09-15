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
import org.apache.http.util.EntityUtils;
import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
    /** 日志记录器，可通过 jmeter.properties 中 log_level.com.llm.perf.sse=DEBUG 控制输出 */
    private static final Logger log = LoggerFactory.getLogger(SseStreamSampler.class);
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
    /** API 类型配置属性键，支持 openai、dify、claude 和 gemini */
    public static final String API_TYPE = "SseStreamSampler.apiType";
    /** API 类型常量：OpenAI */
    public static final String API_TYPE_OPENAI = "openai";
    /** API 类型常量：Dify */
    public static final String API_TYPE_DIFY = "dify";
    /** API 类型常量：Anthropic Claude */
    public static final String API_TYPE_CLAUDE = "claude";
    /** API 类型常量：Google Gemini */
    public static final String API_TYPE_GEMINI = "gemini";
    /** 卡顿判定阈值（毫秒）：相邻 token 间隔超过该值计为一次卡顿 */
    private static final long STALL_THRESHOLD_MS = 1000;

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
        mainResult.setDataType(SampleResult.TEXT);
        // 启动 JMeter 计时，否则 Aggregate Report / BackendListener 的响应时间恒为 0
        mainResult.sampleStart();

        String urlStr = getPropertyAsString(URL);
        String body = getPropertyAsString(REQUEST_BODY);
        int connectTimeout = getPropertyAsInt(CONNECT_TIMEOUT, 10000);
        int readTimeout = getPropertyAsInt(READ_TIMEOUT, 60000);
        String apiType = getPropertyAsString(API_TYPE, API_TYPE_OPENAI);

        // 参数校验
        if (urlStr == null || urlStr.trim().isEmpty()) {
            mainResult.setSuccessful(false);
            mainResult.setResponseMessage("URL不能为空");
            mainResult.sampleEnd();
            return mainResult;
        }
        if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
            mainResult.setSuccessful(false);
            mainResult.setResponseMessage("URL必须以http://或https://开头");
            mainResult.sampleEnd();
            return mainResult;
        }
        if (body == null || body.trim().isEmpty()) {
            mainResult.setSuccessful(false);
            mainResult.setResponseMessage("请求体不能为空");
            mainResult.sampleEnd();
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
        // 未显式指定 Content-Type 时默认使用 application/json（LLM API 的常规要求）
        if (httpPost.getFirstHeader("Content-Type") == null) {
            httpPost.setHeader("Content-Type", "application/json");
        }

        httpPost.setEntity(new StringEntity(body, StandardCharsets.UTF_8));

        BufferedReader reader = null;
        HttpEntity entity = null;
        boolean success = true;
        String responseMsg = "OK";

        try {
            HttpResponse response = HTTP_CLIENT.execute(httpPost);
            int code = response.getStatusLine().getStatusCode();
            if (code >= 300) {
                // HTTP 错误（含 3xx 重定向，POST 不会被 HttpClient 自动跟随）
                success = false;
                responseMsg = "HTTP error code:" + code;
                // 及时消费/关闭响应体，归还连接到连接池，避免连接泄漏
                HttpEntity errEntity = response.getEntity();
                if (errEntity != null) {
                    EntityUtils.consumeQuietly(errEntity);
                }
            } else {
                // 成功响应，开始解析 SSE 流
                entity = response.getEntity();
                if (entity != null) {
                    // 用计时流包装底层输入流，在真正读到首个字节时记录 TTFB
                    InputStream timedStream = new FirstByteTimingInputStream(entity.getContent(), metrics);
                    reader = new BufferedReader(new InputStreamReader(timedStream, StandardCharsets.UTF_8));
                    String line;
                    // 仅开启 DEBUG 日志时才累加流式内容，避免无谓的内存与字符串开销
                    boolean debugLog = log.isDebugEnabled();
                    StringBuilder streamContent = debugLog ? new StringBuilder() : null;

                    // 逐行读取 SSE 流数据
                    while ((line = reader.readLine()) != null) {
                        long now = System.currentTimeMillis();

                        // 记录每条原始 SSE 行（DEBUG 级别，用于逐 chunk 跟踪流）
                        if (log.isDebugEnabled()) {
                            log.debug("[SSE][{}] chunk: {}", getName(), line);
                        }

                        // 解析 SSE 事件
                        SseEvent evt = SseEvent.parse(line);
                        if (evt.isDone()) break;  // 遇到 [DONE] 信号，结束流

                        // 获取 JSON payload，跳过非 data 行
                        JsonObject json = evt.getJsonPayload();
                        if (json == null) continue;

                        // 根据 API 类型处理 token 内容
                        switch (apiType) {
                            case API_TYPE_DIFY:
                                processDifyContent(json, now, metrics, streamContent);
                                break;
                            case API_TYPE_CLAUDE:
                                processClaudeContent(json, now, metrics, streamContent);
                                break;
                            case API_TYPE_GEMINI:
                                processGeminiContent(json, now, metrics, streamContent);
                                break;
                            default:
                                // OpenAI 格式
                                processDeltaContent(json, now, metrics, streamContent);
                                break;
                        }
                        // 处理 usage 信息（token 统计）
                        processUsage(json, metrics);
                    }

                    // 输出完整流式内容，用于验证输出是否正确（DEBUG 级别）
                    if (debugLog) {
                        log.debug("[SSE][{}] full stream content:\n{}", getName(), streamContent.toString());
                    }
                }
            }
        } catch (Exception e) {
            success = false;
            responseMsg = e.getMessage();
            log.error("[SSE][{}] request failed: {}", getName(), e.getMessage(), e);
        } finally {
            if (reader != null) {
                closeQuietly(reader);
            } else if (entity != null) {
                // reader 未创建成功时仍需消费响应体，避免连接泄漏
                EntityUtils.consumeQuietly(entity);
            }
        }

        metrics.requestEndTime = System.currentTimeMillis();

        // 输出本次采样摘要（INFO 级别，用于判断流式结果是否正常）
        if (success) {
            if (log.isInfoEnabled()) {
                log.info("[SSE][{}] url={}, success={}, in/outTokens={}/{}, totalCount={}, TTFT={}ms, TTFB={}ms, TPOT={}ms/token, Token/s={}, RealToken/s={}, MaxGap={}ms, Stall={}, TotalRT={}ms",
                        getName(), urlStr, success, metrics.inputTokens, metrics.outputTokens, metrics.tokenCount,
                        metrics.getTTFT(), metrics.getTTFB(),
                        String.format("%.2f", metrics.getTPOT()),
                        String.format("%.2f", metrics.getTokenPerSec()),
                        String.format("%.2f", metrics.getRealTokenPerSec()),
                        metrics.maxTokenGap, metrics.stallCount,
                        metrics.getTotalRT());
            }
        } else {
            if (log.isWarnEnabled()) {
                log.warn("[SSE][{}] url={}, success={}, responseMsg={}", getName(), urlStr, success, responseMsg);
            }
        }

        mainResult.setSuccessful(success);
        mainResult.setResponseMessage(responseMsg);
        // 延迟取首字节时间（JMeter 语义：latency = time to first byte）
        long ttfb = metrics.getTTFB();
        if (ttfb >= 0) {
            mainResult.setLatency(ttfb);
        }

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
        sb.append("RealToken/s:").append(String.format("%.2f", metrics.getRealTokenPerSec())).append("\n");
        sb.append("TTFT-TTFB(ms):").append(metrics.getTTFTMinusTTFB()).append("\n");
        sb.append("MaxGap(ms):").append(metrics.maxTokenGap).append("\n");
        sb.append("Stall(>1s):").append(metrics.stallCount).append("\n");
        mainResult.setSamplerData(sb.toString());

        // 结束 JMeter 计时，填充响应时间/结束时间戳
        mainResult.sampleEnd();
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
     * @param streamContent 流式内容累加器
     */
    private void processDeltaContent(JsonObject json, long now, SseMetrics metrics, StringBuilder streamContent) {
        if (!json.has("choices") || json.get("choices").isJsonNull()) return;
        JsonArray choicesArr = json.getAsJsonArray("choices");
        if (choicesArr == null || choicesArr.size() == 0) return;

        JsonObject choice = choicesArr.get(0).getAsJsonObject();
        if (!choice.has("delta")) return;

        JsonObject delta = choice.getAsJsonObject("delta");
        if (!delta.has("content") || delta.get("content").isJsonNull()) return;

        String content = delta.get("content").getAsString();
        if (content != null && !content.isEmpty()) {
            appendStreamContent(streamContent, content);
            recordToken(now, metrics);
        }
    }

    /**
     * 处理 Dify 格式的 SSE 事件。
     *
     * <p>Dify 格式的 SSE 响应结构：</p>
     * <pre>
     * data: {"event":"message","answer":"Hello"}
     * data: {"event":"agent_message","answer":" world"}
     * data: {"event":"message_end","metadata":{"usage":{...}}}
     * </pre>
     *
     * <p>支持的事件类型：</p>
     * <ul>
     *   <li>message - 消息事件，包含 answer 字段</li>
     *   <li>agent_message - Agent 消息事件，包含 answer 字段</li>
     *   <li>text_chunk - 文本块事件，包含 text 字段</li>
     * </ul>
     *
     * @param json SSE 事件的 JSON payload
     * @param now 当前时间戳（毫秒）
     * @param metrics 指标收集器
     * @param streamContent 流式内容累加器
     */
    private void processDifyContent(JsonObject json, long now, SseMetrics metrics, StringBuilder streamContent) {
        if (!json.has("event") || json.get("event").isJsonNull()) return;

        String eventType = json.get("event").getAsString();
        if (eventType == null) return;

        String content = null;

        // 根据事件类型提取内容
        switch (eventType) {
            case "message":
            case "agent_message":
                // 消息事件，从 answer 字段提取内容
                if (json.has("answer") && !json.get("answer").isJsonNull()) {
                    content = json.get("answer").getAsString();
                }
                break;
            case "text_chunk":
                // 文本块事件（chatflow/workflow 模式）：text 嵌套在 data 字段中
                // {"event":"text_chunk","data":{"position":1,"text":"..."}}
                if (json.has("data") && !json.get("data").isJsonNull()) {
                    JsonObject dataObj = json.getAsJsonObject("data");
                    if (dataObj.has("text") && !dataObj.get("text").isJsonNull()) {
                        content = dataObj.get("text").getAsString();
                    }
                }
                // 兼容旧格式：顶层 text 字段
                if (content == null && json.has("text") && !json.get("text").isJsonNull()) {
                    content = json.get("text").getAsString();
                }
                break;
            default:
                // 其他事件类型（如 workflow_started, node_finished 等）不处理
                return;
        }

        // 处理提取到的内容
        if (content != null && !content.isEmpty()) {
            appendStreamContent(streamContent, content);
            recordToken(now, metrics);
        }
    }

    /**
     * 处理各类 API 的 usage（token 统计）信息。
     *
     * <p>不同 API 的 usage 位置和字段名不同，统一兜底解析：</p>
     * <ul>
     *   <li>OpenAI：顶层 usage（prompt_tokens / completion_tokens），需要在请求体中包含
     *       {@code "stream_options":{"include_usage":true}} 才会返回 usage chunk</li>
     *   <li>Dify：metadata.usage（prompt_tokens / completion_tokens），由 message_end 事件携带</li>
     *   <li>Gemini：顶层 usageMetadata / usage_metadata（promptTokenCount / candidatesTokenCount）</li>
     *   <li>Claude：message_start / message_delta 事件中的 usage 字段，在
     *       {@link #processClaudeContent} 中解析</li>
     * </ul>
     *
     * @param json SSE 事件的 JSON payload
     * @param metrics 指标收集器
     */
    private void processUsage(JsonObject json, SseMetrics metrics) {
        JsonObject usage = null;

        // 1) 顶层 usage（OpenAI / 部分兼容服务）
        if (json.has("usage") && !json.get("usage").isJsonNull()) {
            usage = json.getAsJsonObject("usage");
        }
        // 2) Dify：metadata.usage
        if (usage == null && json.has("metadata") && !json.get("metadata").isJsonNull()) {
            JsonObject metadata = json.getAsJsonObject("metadata");
            if (metadata.has("usage") && !metadata.get("usage").isJsonNull()) {
                usage = metadata.getAsJsonObject("usage");
            }
        }
        // 3) Gemini：usageMetadata / usage_metadata
        if (usage == null) {
            if (json.has("usageMetadata") && !json.get("usageMetadata").isJsonNull()) {
                usage = json.getAsJsonObject("usageMetadata");
            } else if (json.has("usage_metadata") && !json.get("usage_metadata").isJsonNull()) {
                usage = json.getAsJsonObject("usage_metadata");
            }
        }
        if (usage == null) return;

        // OpenAI 命名：prompt_tokens / completion_tokens
        if (usage.has("prompt_tokens")) {
            metrics.inputTokens = usage.get("prompt_tokens").getAsLong();
        }
        if (usage.has("completion_tokens")) {
            metrics.outputTokens = usage.get("completion_tokens").getAsLong();
        }
        // Gemini 命名：promptTokenCount / candidatesTokenCount
        if (usage.has("promptTokenCount")) {
            metrics.inputTokens = usage.get("promptTokenCount").getAsLong();
        }
        if (usage.has("candidatesTokenCount")) {
            metrics.outputTokens = usage.get("candidatesTokenCount").getAsLong();
        }
        // Claude 命名：input_tokens / output_tokens
        if (usage.has("input_tokens")) {
            metrics.inputTokens = usage.get("input_tokens").getAsLong();
        }
        if (usage.has("output_tokens")) {
            metrics.outputTokens = usage.get("output_tokens").getAsLong();
        }
    }

    /**
     * 处理 Anthropic Claude 格式的 SSE 事件。
     *
     * <p>Claude 格式的 SSE 响应结构：</p>
     * <pre>
     * event: message_start
     * data: {"type":"message_start","message":{"id":"msg_...","type":"message","role":"assistant","model":"claude-3-opus-20240229","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":11,"output_tokens":1}}}
     * 
     * event: content_block_start
     * data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}
     * 
     * event: content_block_delta
     * data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
     * 
     * event: content_block_stop
     * data: {"type":"content_block_stop","index":0}
     * 
     * event: message_delta
     * data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":15}}
     * 
     * event: message_stop
     * data: {"type":"message_stop"}
     * </pre>
     *
     * <p>主要处理 content_block_delta 事件中的 text_delta 文本内容。</p>
     *
     * @param json SSE 事件的 JSON payload
     * @param now 当前时间戳（毫秒）
     * @param metrics 指标收集器
     * @param streamContent 流式内容累加器
     */
    private void processClaudeContent(JsonObject json, long now, SseMetrics metrics, StringBuilder streamContent) {
        if (!json.has("type")) return;

        String eventType = json.get("type").getAsString();
        if (eventType == null) return;

        // 处理 content_block_delta 事件，提取文本内容
        if ("content_block_delta".equals(eventType)) {
            if (!json.has("delta") || json.get("delta").isJsonNull()) return;
            JsonObject delta = json.getAsJsonObject("delta");
            if (!delta.has("text") || delta.get("text").isJsonNull()) return;

            String text = delta.get("text").getAsString();
            if (text != null && !text.isEmpty()) {
                appendStreamContent(streamContent, text);
                recordToken(now, metrics);
            }
        }
        // 处理 message_start 事件，提取 input_tokens（usage 嵌套在 message 字段中）
        if ("message_start".equals(eventType)) {
            if (json.has("message") && !json.get("message").isJsonNull()) {
                JsonObject message = json.getAsJsonObject("message");
                if (message.has("usage") && !message.get("usage").isJsonNull()) {
                    JsonObject usage = message.getAsJsonObject("usage");
                    if (usage.has("input_tokens")) {
                        metrics.inputTokens = usage.get("input_tokens").getAsLong();
                    }
                    if (usage.has("output_tokens")) {
                        metrics.outputTokens = usage.get("output_tokens").getAsLong();
                    }
                }
            }
        }
        // 处理 message_delta 事件，提取 usage 信息
        else if ("message_delta".equals(eventType)) {
            if (json.has("usage") && !json.get("usage").isJsonNull()) {
                JsonObject usage = json.getAsJsonObject("usage");
                if (usage.has("output_tokens")) {
                    metrics.outputTokens = usage.get("output_tokens").getAsLong();
                }
            }
        }
    }

    /**
     * 处理 Google Gemini 格式的 SSE 事件。
     *
     * <p>Gemini Interactions API 格式的 SSE 响应结构：</p>
     * <pre>
     * data: {"event_type":"step.start","step":{"id":"...","type":"model_output","index":0}}
     * data: {"event_type":"step.delta","step":{"id":"...","type":"model_output","index":0},"delta":{"type":"text","text":"Hello"}}
     * data: {"event_type":"step.stop","step":{"id":"...","type":"model_output","index":0}}
     * data: {"event_type":"interaction.completed","usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":20,"totalTokenCount":30}}
     * </pre>
     *
     * <p>主要处理 step.delta 事件中的 text delta 内容。</p>
     *
     * @param json SSE 事件的 JSON payload
     * @param now 当前时间戳（毫秒）
     * @param metrics 指标收集器
     * @param streamContent 流式内容累加器
     */
    private void processGeminiContent(JsonObject json, long now, SseMetrics metrics, StringBuilder streamContent) {
        if (!json.has("event_type")) return;

        String eventType = json.get("event_type").getAsString();
        if (eventType == null) return;

        // 处理 step.delta 事件，提取文本内容
        if ("step_delta".equals(eventType) || "step.delta".equals(eventType)) {
            if (!json.has("delta") || json.get("delta").isJsonNull()) return;
            JsonObject delta = json.getAsJsonObject("delta");
            if (!delta.has("text") || delta.get("text").isJsonNull()) return;

            String text = delta.get("text").getAsString();
            if (text != null && !text.isEmpty()) {
                appendStreamContent(streamContent, text);
                recordToken(now, metrics);
            }
        }
        // 处理 usage 信息（token 统计）
        else if ("interaction_completed".equals(eventType) || "interaction.completed".equals(eventType)
                || "step.completed".equals(eventType) || "step_completed".equals(eventType)
                || "message.completed".equals(eventType) || "message_completed".equals(eventType)) {
            parseGeminiUsage(json, metrics);
        }
    }

    /**
     * 解析 Gemini 的 usageMetadata 信息。
     *
     * <p>当对象包含 usageMetadata 时，提取 token 统计字段：</p>
     * <ul>
     *   <li>promptTokenCount - 输入 token 数</li>
     *   <li>candidatesTokenCount - 输出 token 数</li>
     * </ul>
     *
     * <p>注意：不处理 totalTokenCount，避免覆盖 {@code tokenCount}（该字段语义为
     * "逐 chunk 累加数"，TPOT / Token/s 依赖其一致的语义；真实总吞吐用 outputTokens）。</p>
     *
     * @param json SSE 事件的 JSON payload
     * @param metrics 指标收集器
     */
    private void parseGeminiUsage(JsonObject json, SseMetrics metrics) {
        JsonObject usage = null;
        if (json.has("usageMetadata") && !json.get("usageMetadata").isJsonNull()) {
            usage = json.getAsJsonObject("usageMetadata");
        } else if (json.has("usage_metadata") && !json.get("usage_metadata").isJsonNull()) {
            usage = json.getAsJsonObject("usage_metadata");
        }
        if (usage == null) return;

        if (usage.has("promptTokenCount")) {
            metrics.inputTokens = usage.get("promptTokenCount").getAsLong();
        }
        if (usage.has("candidatesTokenCount")) {
            metrics.outputTokens = usage.get("candidatesTokenCount").getAsLong();
        }
    }

    /**
     * 追加流式内容到日志累加器（DEBUG 未开启时 streamContent 为 null，跳过）。
     *
     * @param streamContent 累加器，可能为 null
     * @param text 追加的文本
     */
    private void appendStreamContent(StringBuilder streamContent, String text) {
        if (streamContent != null) {
            streamContent.append(text);
        }
    }

    /**
     * 记录一次 token/content 到达，更新 TTFT、lastTokenTime、token 间隔与卡顿统计。
     *
     * @param now 当前时间戳（毫秒）
     * @param metrics 指标收集器
     */
    private void recordToken(long now, SseMetrics metrics) {
        if (metrics.firstTokenTime == -1) {
            // 首次收到有效内容时记录 TTFT
            metrics.firstTokenTime = now;
        } else {
            // 计算相邻 token 间隔，用于断流/卡顿检测
            long gap = now - metrics.lastTokenTime;
            if (gap > metrics.maxTokenGap) {
                metrics.maxTokenGap = gap;
            }
            if (gap > STALL_THRESHOLD_MS) {
                metrics.stallCount++;
            }
        }
        metrics.lastTokenTime = now;
        metrics.tokenCount++;
    }

    /**
     * 首字节计时输入流。
     *
     * <p>包装响应体输入流，在首次真正读到字节时记录 TTFB，
     * 避免用 {@code BufferedReader.readLine()} 返回时间近似（会把 TTFB 记为
     * 首个完整 SSE 行的到达时间，而非首字节时间）。</p>
     */
    private static final class FirstByteTimingInputStream extends FilterInputStream {
        private final SseMetrics metrics;
        private boolean firstByteRecorded = false;

        FirstByteTimingInputStream(InputStream in, SseMetrics metrics) {
            super(in);
            this.metrics = metrics;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                recordFirstByte();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                recordFirstByte();
            }
            return n;
        }

        private void recordFirstByte() {
            if (!firstByteRecorded) {
                metrics.firstByteTime = System.currentTimeMillis();
                firstByteRecorded = true;
            }
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
