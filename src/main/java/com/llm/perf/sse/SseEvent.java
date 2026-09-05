package com.llm.perf.sse;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;

/**
 * SSE (Server-Sent Events) 事件解析器。
 *
 * <p>解析 SSE 协议中的单行数据，提取 JSON payload。</p>
 *
 * <p>SSE 协议格式示例：</p>
 * <pre>
 * data: {"choices":[{"delta":{"content":"Hello"}}]}
 * data: {"choices":[{"delta":{"content":" world"}}]}
 * data: [DONE]
 * </pre>
 *
 * @author liwen
 * Date  2025-09-03
 */
public class SseEvent {
    /** SSE 流结束标记 */
    public static final String DONE = "[DONE]";
    /** 解析出的 data 字段内容 */
    private String data;

    /**
     * 解析 SSE 行数据。
     *
     * <p>只处理以 "data:" 开头的行，忽略其他行（如注释行、event 行等）。</p>
     *
     * @param line SSE 协议中的一行数据
     * @return 解析后的 SSE 事件对象
     */
    public static SseEvent parse(String line) {
        SseEvent evt = new SseEvent();
        if (line.startsWith("data:")) {
            evt.data = line.substring(5).trim();
        }
        return evt;
    }

    /**
     * 判断是否为流结束标记。
     *
     * @return 如果 data 等于 "[DONE]" 则返回 true
     */
    public boolean isDone() {
        return DONE.equals(data);
    }

    /**
     * 获取 JSON payload。
     *
     * <p>将 data 字段解析为 JSON 对象。如果 data 为空、是结束标记或
     * 解析失败，则返回 null。</p>
     *
     * @return 解析后的 JSON 对象，失败返回 null
     */
    public JsonObject getJsonPayload() {
        if (data == null || isDone() || data.isEmpty()) {
            return null;
        }
        try {
            com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
            try (JsonReader reader = new JsonReader(new java.io.StringReader(data))) {
                reader.setLenient(true);
                return parser.parse(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
