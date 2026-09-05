package com.llm.perf.sse;

/**
 * SSE 流式 LLM API 性能指标数据模型。
 *
 * <p>存储一次 SSE 流式请求的原始时间戳和 token 统计数据，
 * 并提供以下派生指标的计算方法：</p>
 * <ul>
 *   <li>TTFT (Time To First Token) - 首个 token 延迟</li>
 *   <li>TTFB (Time To First Byte) - 首字节延迟</li>
 *   <li>TPOT (Time Per Output Token) - 每个输出 token 耗时</li>
 *   <li>Token/s - 吞吐率</li>
 *   <li>TotalRT (Total Response Time) - 总响应时间</li>
 *   <li>Streaming Duration - 流式生成持续时间</li>
 * </ul>
 *
 * <p>所有时间戳均为毫秒级，异常情况返回 -1 或 0。</p>
 *
 * @author liwen
 * Date  2025-09-03
 */
public class SseMetrics {
    /** 请求开始时间戳（毫秒） */
    public long requestStartTime;
    /** 首字节到达时间戳（毫秒），-1 表示未设置 */
    public long firstByteTime = -1L;
    /** 首个 token 到达时间戳（毫秒），-1 表示未设置 */
    public long firstTokenTime = -1L;
    /** 最后一个 token 到达时间戳（毫秒），-1 表示未设置 */
    public long lastTokenTime = -1L;
    /** 请求结束时间戳（毫秒） */
    public long requestEndTime;

    /** 输入 token 数量（来自 usage.prompt_tokens） */
    public long inputTokens = 0;
    /** 输出 token 数量（来自 usage.completion_tokens） */
    public long outputTokens = 0;
    /** 已接收的 token 总数（逐 chunk 累加） */
    public long tokenCount = 0;

    /**
     * 计算 TTFT (Time To First Token) - 首个 token 延迟。
     *
     * <p>计算公式：firstTokenTime - requestStartTime</p>
     *
     * @return 延迟毫秒数，异常返回 -1
     */
    public long getTTFT() {
        if (firstTokenTime <= 0 || requestStartTime <=0) {
            return -1;
        }
        return Math.max(0, firstTokenTime - requestStartTime);
    }

    /**
     * 计算 TTFB (Time To First Byte) - 首字节延迟。
     *
     * <p>计算公式：firstByteTime - requestStartTime</p>
     *
     * @return 延迟毫秒数，异常返回 -1
     */
    public long getTTFB() {
        if (firstByteTime <= 0 || requestStartTime <=0) {
            return -1;
        }
        return Math.max(0, firstByteTime - requestStartTime);
    }

    /**
     * 计算 TotalRT (Total Response Time) - 总响应时间。
     *
     * <p>计算公式：requestEndTime - requestStartTime</p>
     *
     * @return 响应时间毫秒数，异常返回 -1
     */
    public long getTotalRT() {
        if(requestEndTime <=0 || requestStartTime <=0){
            return -1;
        }
        return Math.max(0, requestEndTime - requestStartTime);
    }

    /**
     * 计算 TPOT (Time Per Output Token) - 每个输出 token 平均耗时。
     *
     * <p>计算公式：(lastTokenTime - firstTokenTime) / (tokenCount - 1)</p>
     * <p>需要至少 2 个 token 才能计算，否则返回 -1。</p>
     *
     * @return 毫秒/token，异常返回 -1
     */
    public double getTPOT() {
        if (firstTokenTime <= 0 || lastTokenTime <= 0 || tokenCount <= 1) {
            return -1d;
        }
        long duration = lastTokenTime - firstTokenTime;
        if(duration <=0){
            return -1d;
        }
        return (double) duration / (tokenCount - 1);
    }

    /**
     * 计算 Token/s - token 生成速率。
     *
     * <p>计算公式：tokenCount / duration * 1000</p>
     * <p>需要至少 1 个 token 且持续时间大于 0。</p>
     *
     * @return token/秒，异常返回 -1
     */
    public double getTokenPerSec() {
        if (firstTokenTime <= 0 || lastTokenTime <= 0) {
            return -1d;
        }
        long duration = lastTokenTime - firstTokenTime;
        if (duration <= 0) {
            return -1d;
        }
        return (double) tokenCount / duration * 1000;
    }

    /**
     * 计算流式生成持续时间：从第一个 token 到最后一个 token。
     *
     * <p>计算公式：lastTokenTime - firstTokenTime</p>
     *
     * @return 持续时间毫秒数，异常返回 0
     */
    public long getStreamingDuration() {
        if (firstTokenTime <= 0 || lastTokenTime <= 0) {
            return 0;
        }
        long diff = lastTokenTime - firstTokenTime;
        return Math.max(0, diff);
    }

    /**
     * 返回指标的可读字符串表示。
     *
     * @return 包含所有指标的格式化字符串
     */
    @Override
    public String toString() {
        return "SseMetrics{" +
                "requestStartTime=" + requestStartTime +
                ", firstByteTime=" + firstByteTime +
                ", firstTokenTime=" + firstTokenTime +
                ", lastTokenTime=" + lastTokenTime +
                ", requestEndTime=" + requestEndTime +
                ", inputTokens=" + inputTokens +
                ", outputTokens=" + outputTokens +
                ", tokenCount=" + tokenCount +
                ", TTFT(ms)=" + getTTFT() +
                ", TTFB(ms)=" + getTTFB() +
                ", TotalRT(ms)=" + getTotalRT() +
                ", TPOT(ms/token)=" + String.format("%.2f",getTPOT()) +
                ", TokenPerSec(token/s)=" + String.format("%.2f",getTokenPerSec()) +
                ", streamingDuration(ms)=" + getStreamingDuration() +
                '}';
    }
}
