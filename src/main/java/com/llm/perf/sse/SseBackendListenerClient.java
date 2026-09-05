package com.llm.perf.sse;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.visualizers.backend.AbstractBackendListenerClient;
import org.apache.jmeter.visualizers.backend.BackendListenerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE-LLM 指标后端监听器客户端。
 *
 * <p>将 SSE 流式 LLM API 的性能指标批量推送到外部后端系统（如 InfluxDB）。
 * 数据格式遵循 InfluxDB Line Protocol 的 JSON 表示。</p>
 *
 * <p>主要特性：</p>
 * <ul>
 *   <li>异步批量推送，避免影响采样性能</li>
 *   <li>可配置推送间隔和批次大小</li>
 *   <li>支持自定义认证令牌</li>
 *   <li>失败时自动重试（数据放回缓冲区）</li>
 * </ul>
 *
 * <p>配置参数：</p>
 * <ul>
 *   <li>endpointUrl - 推送目标 URL（如 http://localhost:8086/write?db=jmeter）</li>
 *   <li>authToken - 认证令牌（可选）</li>
 *   <li>flushIntervalMs - 推送间隔（毫秒，默认 5000）</li>
 *   <li>batchSize - 批次大小（默认 50）</li>
 *   <li>application - 应用标签（默认 jmeter-sse）</li>
 *   <li>measurement - 测量名称（默认 sse_llm_metrics）</li>
 * </ul>
 *
 * @author liwen
 * Date  2025-09-03
 */
public class SseBackendListenerClient extends AbstractBackendListenerClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(SseBackendListenerClient.class);

    /** 推送目标 URL 参数名 */
    private static final String PARAM_ENDPOINT_URL = "endpointUrl";
    /** 认证令牌参数名 */
    private static final String PARAM_AUTH_TOKEN = "authToken";
    /** 推送间隔（毫秒）参数名 */
    private static final String PARAM_FLUSH_INTERVAL_MS = "flushIntervalMs";
    /** 批次大小参数名 */
    private static final String PARAM_BATCH_SIZE = "batchSize";
    /** 应用标签参数名 */
    private static final String PARAM_APPLICATION_TAG = "application";
    /** 测量名称参数名 */
    private static final String PARAM_MEASUREMENT = "measurement";

    /** 默认推送间隔：5000 毫秒 */
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 5000;
    /** 默认批次大小：50 条 */
    private static final int DEFAULT_BATCH_SIZE = 50;

    /** 推送目标 URL */
    private String endpointUrl;
    /** 认证令牌 */
    private String authToken;
    /** 应用标签 */
    private String application;
    /** 测量名称 */
    private String measurement;
    /** 批次大小 */
    private int batchSize;

    /** 数据缓冲区，线程安全的无界队列 */
    private final ConcurrentLinkedQueue<JsonObject> buffer = new ConcurrentLinkedQueue<>();
    /** 定时推送调度器 */
    private ScheduledExecutorService scheduler;
    /** HTTP 客户端 */
    private CloseableHttpClient httpClient;
    /** JSON 序列化器 */
    private final Gson gson = new Gson();

    /**
     * 初始化后端监听器。
     *
     * <p>配置 HTTP 客户端、启动定时推送任务。</p>
     *
     * @param context 后端监听器上下文，包含配置参数
     */
    @Override
    public void setupTest(BackendListenerContext context) throws Exception {
        endpointUrl = context.getParameter(PARAM_ENDPOINT_URL, "");
        authToken = context.getParameter(PARAM_AUTH_TOKEN, "");
        application = context.getParameter(PARAM_APPLICATION_TAG, "jmeter-sse");
        measurement = context.getParameter(PARAM_MEASUREMENT, "sse_llm_metrics");
        int flushInterval = context.getIntParameter(PARAM_FLUSH_INTERVAL_MS, DEFAULT_FLUSH_INTERVAL_MS);
        batchSize = context.getIntParameter(PARAM_BATCH_SIZE, DEFAULT_BATCH_SIZE);

        // 配置 HTTP 客户端
        httpClient = HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(5000)
                        .setSocketTimeout(10000)
                        .build())
                .build();

        // 启动定时推送任务
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SseBackendListener-Flush");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::flush, flushInterval, flushInterval, TimeUnit.MILLISECONDS);

        log.info("SseBackendListener setup: endpoint={}, app={}, measurement={}, flushInterval={}ms, batchSize={}",
                endpointUrl, application, measurement, flushInterval, batchSize);
    }

    /**
     * 清理资源，确保所有缓冲数据被推送。
     *
     * @param context 后端监听器上下文
     */
    @Override
    public void teardownTest(BackendListenerContext context) throws Exception {
        flush();  // 最后一次推送剩余数据
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (httpClient != null) {
            httpClient.close();
        }
        log.info("SseBackendListener teardown: total buffered={}", buffer.size());
    }

    /**
     * 处理采样结果，提取指标并加入缓冲区。
     *
     * <p>从 {@link SampleResult#getResponseDataAsString()} 读取 JSON 格式的 {@link SseMetrics}，
     * 构建 InfluxDB 格式的数据点并加入缓冲区。</p>
     *
     * @param results 采样结果列表
     * @param context 后端监听器上下文
     */
    @Override
    public void handleSampleResults(List<SampleResult> results, BackendListenerContext context) {
        for (SampleResult result : results) {
            String data = result.getResponseDataAsString();
            if (data == null || data.isEmpty()) continue;

            try {
                SseMetrics metrics = gson.fromJson(data, SseMetrics.class);
                JsonObject point = buildPoint(result, metrics);
                buffer.offer(point);

                // 达到批次大小时立即推送
                if (buffer.size() >= batchSize) {
                    flush();
                }
            } catch (Exception e) {
                log.warn("Failed to parse SSE metrics: {}", e.getMessage());
            }
        }
    }

    /**
     * 构建 InfluxDB 格式的数据点。
     *
     * @param result JMeter 采样结果
     * @param metrics SSE 性能指标
     * @return JSON 格式的数据点
     */
    private JsonObject buildPoint(SampleResult result, SseMetrics metrics) {
        JsonObject point = new JsonObject();
        point.addProperty("measurement", measurement);

        // 标签（用于索引和查询）
        JsonObject tags = new JsonObject();
        tags.addProperty("application", application);
        tags.addProperty("sampler", result.getSampleLabel());
        tags.addProperty("success", String.valueOf(result.isSuccessful()));
        point.add("tags", tags);

        // 字段（实际数据值）
        JsonObject fields = new JsonObject();
        fields.addProperty("ttft", metrics.getTTFT());
        fields.addProperty("ttfb", metrics.getTTFB());
        fields.addProperty("tpot", metrics.getTPOT());
        fields.addProperty("token_per_sec", metrics.getTokenPerSec());
        fields.addProperty("total_rt", metrics.getTotalRT());
        fields.addProperty("input_tokens", metrics.inputTokens);
        fields.addProperty("output_tokens", metrics.outputTokens);
        fields.addProperty("token_count", metrics.tokenCount);
        fields.addProperty("response_time", result.getTime());
        fields.addProperty("latency", result.getLatency());
        fields.addProperty("connect_time", result.getConnectTime());
        point.add("fields", fields);

        // 纳秒级时间戳（InfluxDB 要求）
        point.addProperty("timestamp", System.currentTimeMillis() * 1000000);

        return point;
    }

    /**
     * 批量推送缓冲数据到后端。
     *
     * <p>从缓冲区取出最多 1000 条数据，构建 JSON 数组并 POST 到 endpointUrl。
     * 如果推送失败（HTTP 状态码 >= 300 或异常），数据将放回缓冲区重试。</p>
     */
    private void flush() {
        if (buffer.isEmpty() || endpointUrl.isEmpty()) return;

        // 取出缓冲数据，最多 1000 条
        List<JsonObject> batch = new ArrayList<>();
        JsonObject item;
        while ((item = buffer.poll()) != null) {
            batch.add(item);
            if (batch.size() >= 1000) break;
        }

        if (batch.isEmpty()) return;

        try {
            String json = gson.toJson(batch);
            HttpPost post = new HttpPost(endpointUrl);
            post.setHeader("Content-Type", "application/json");
            if (authToken != null && !authToken.isEmpty()) {
                post.setHeader("Authorization", authToken);
            }
            post.setEntity(new StringEntity(json, StandardCharsets.UTF_8));

            int code = httpClient.execute(post, response -> response.getStatusLine().getStatusCode());
            if (code >= 300) {
                log.warn("Backend flush returned HTTP {}", code);
                buffer.addAll(batch);  // 失败时放回缓冲区
            } else {
                log.debug("Flushed {} points to {}", batch.size(), endpointUrl);
            }
        } catch (Exception e) {
            log.error("Backend flush failed: {}", e.getMessage());
            buffer.addAll(batch);  // 异常时放回缓冲区
        }
    }

    /**
     * 返回默认参数配置。
     *
     * @return 默认参数列表
     */
    @Override
    public Arguments getDefaultParameters() {
        Arguments args = new Arguments();
        args.addArgument(PARAM_ENDPOINT_URL, "http://localhost:8086/write?db=jmeter");
        args.addArgument(PARAM_AUTH_TOKEN, "");
        args.addArgument(PARAM_FLUSH_INTERVAL_MS, String.valueOf(DEFAULT_FLUSH_INTERVAL_MS));
        args.addArgument(PARAM_BATCH_SIZE, String.valueOf(DEFAULT_BATCH_SIZE));
        args.addArgument(PARAM_APPLICATION_TAG, "jmeter-sse");
        args.addArgument(PARAM_MEASUREMENT, "sse_llm_metrics");
        return args;
    }

    /**
     * 关闭资源，确保数据被推送。
     */
    @Override
    public void close() {
        try {
            teardownTest(null);
        } catch (Exception ignored) {
        }
    }
}
