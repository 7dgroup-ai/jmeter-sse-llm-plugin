package com.llm.perf.sse;

import com.google.gson.Gson;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * SSE-LLM 指标监听器 GUI 面板。
 *
 * <p>实时展示流式 LLM API 的性能指标聚合统计，包括：</p>
 * <ul>
 *   <li>TTFT (Time To First Token) - 首个 token 延迟的平均值、最小值、最大值</li>
 *   <li>TTFB (Time To First Byte) - 首字节延迟</li>
 *   <li>TPOT (Time Per Output Token) - 每个输出 token 耗时</li>
 *   <li>Token/s - 吞吐率</li>
 *   <li>TotalRT (Total Response Time) - 总响应时间</li>
 *   <li>Input/Output Tokens - 输入/输出 token 数量</li>
 * </ul>
 *
 * <p>数据来源：从 {@link SseStreamSampler} 产生的 {@link SampleResult} 中
 * 读取 JSON 格式的 {@link SseMetrics} 进行聚合计算。</p>
 *
 * @author liwen
 * Date  2025-09-03
 */
public class SseMetricsListenerGui extends AbstractVisualizer {
    private static final long serialVersionUID = 1L;
    /** 日志面板最大显示条数 */
    private static final int MAX_LOG_ENTRIES = 500;

    /** 采样计数 */
    private long sampleCount = 0;
    /** TTFT/TTFB/TotalRT 累加值 */
    private long sumTtft = 0, sumTtfb = 0, sumTotalRt = 0;
    /** TPOT/Tps 累加值 */
    private double sumTpot = 0, sumTps = 0;
    /** 输入/输出 token 总数 */
    private long totalIn = 0, totalOut = 0;
    /** 各指标有效样本计数（用于计算平均值） */
    private int validTtft = 0, validTtfb = 0, validTpot = 0, validTps = 0, validTotalRt = 0;
    /** TTFT 的最小值和最大值 */
    private long minTtft = Long.MAX_VALUE, maxTtft = Long.MIN_VALUE;

    /** 日志数据模型 */
    private final DefaultListModel<String> logModel = new DefaultListModel<>();
    /** 日志列表组件 */
    private JList<String> logList;
    /** 统计标签组件 */
    private JLabel countLabel;
    private JLabel avgTtftLabel, minTtftLabel, maxTtftLabel;
    private JLabel avgTtfbLabel;
    private JLabel avgTpotLabel;
    private JLabel avgTpsLabel;
    private JLabel avgTotalRtLabel;
    private JLabel totalInputTokensLabel, totalOutputTokensLabel;
    /** JSON 反序列化器 */
    private final Gson gson = new Gson();
    /** 时间格式化器 */
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    public SseMetricsListenerGui() {
        init();
    }

    /**
     * 初始化 GUI 组件。
     */
    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        // 聚合统计面板
        JPanel statsPanel = new JPanel();
        statsPanel.setLayout(new BoxLayout(statsPanel, BoxLayout.X_AXIS));
        statsPanel.setBorder(BorderFactory.createTitledBorder("Aggregate Statistics"));

        countLabel = new JLabel("0");
        avgTtftLabel = new JLabel("-");
        minTtftLabel = new JLabel("-");
        maxTtftLabel = new JLabel("-");
        avgTtfbLabel = new JLabel("-");
        avgTpotLabel = new JLabel("-");
        avgTpsLabel = new JLabel("-");
        avgTotalRtLabel = new JLabel("-");
        totalInputTokensLabel = new JLabel("0");
        totalOutputTokensLabel = new JLabel("0");

        statsPanel.add(createStatItem("Samples:", countLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("TTFT avg(ms):", avgTtftLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("min:", minTtftLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("max:", maxTtftLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("TTFB(ms):", avgTtfbLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("TPOT(ms/t):", avgTpotLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("Token/s:", avgTpsLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("TotalRT(ms):", avgTotalRtLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("In:", totalInputTokensLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("Out:", totalOutputTokensLabel));

        add(statsPanel, BorderLayout.NORTH);

        // 样本日志面板
        logList = new JList<>(logModel);
        logList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(logList);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Sample Log (last " + MAX_LOG_ENTRIES + ")"));
        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * 接收新的采样结果并更新统计。
     *
     * <p>从 {@link SampleResult#getResponseDataAsString()} 读取 JSON 格式的 {@link SseMetrics}，
     * 累加各项指标并更新 GUI 显示。</p>
     *
     * @param result JMeter 采样结果
     */
    @Override
    public void add(SampleResult result) {
        String data = result.getResponseDataAsString();
        if (data == null || data.isEmpty()) return;

        try {
            SseMetrics metrics = gson.fromJson(data, SseMetrics.class);

            sampleCount++;

            // 累加有效指标（>= 0 表示有有效数据）
            long ttft = metrics.getTTFT();
            if (ttft >= 0) {
                sumTtft += ttft;
                validTtft++;
                minTtft = Math.min(minTtft, ttft);
                maxTtft = Math.max(maxTtft, ttft);
            }
            if (metrics.getTTFB() >= 0) { sumTtfb += metrics.getTTFB(); validTtfb++; }
            if (metrics.getTPOT() >= 0) { sumTpot += metrics.getTPOT(); validTpot++; }
            if (metrics.getTokenPerSec() >= 0) { sumTps += metrics.getTokenPerSec(); validTps++; }
            if (metrics.getTotalRT() >= 0) { sumTotalRt += metrics.getTotalRT(); validTotalRt++; }
            totalIn += metrics.inputTokens;
            totalOut += metrics.outputTokens;

            // 构建日志条目
            String timestamp = sdf.format(new Date());
            String entry = String.format(
                    "[%s] TTFT=%d TTFB=%d TPOT=%.2f Tps=%.2f TotalRT=%d in=%d out=%d n=%d",
                    timestamp, ttft, metrics.getTTFB(),
                    metrics.getTPOT(), metrics.getTokenPerSec(), metrics.getTotalRT(),
                    metrics.inputTokens, metrics.outputTokens, metrics.tokenCount);

            // 在 EDT 线程中更新 GUI
            SwingUtilities.invokeLater(() -> {
                // 保持日志条数不超过上限，超过时移除最早的条目
                if (logModel.size() >= MAX_LOG_ENTRIES) {
                    logModel.removeRange(0, logModel.size() - MAX_LOG_ENTRIES + 10);
                }
                logModel.addElement(entry);
                updateStatsLabels();
            });
        } catch (Exception ignored) {
            // 静默忽略解析异常，避免影响其他采样结果
        }
    }

    /**
     * 创建统计项面板（标签 + 值）。
     *
     * @param label 统计项名称
     * @param valueLabel 值标签组件
     * @return 包含标签和值的面板
     */
    private JPanel createStatItem(String label, JLabel valueLabel) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JLabel lbl = new JLabel(label);
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        p.add(lbl);
        p.add(valueLabel);
        return p;
    }

    /**
     * 更新所有统计标签的显示值。
     */
    private void updateStatsLabels() {
        countLabel.setText(String.valueOf(sampleCount));
        avgTtftLabel.setText(validTtft > 0 ? String.format("%.1f", sumTtft / (double) validTtft) : "-");
        minTtftLabel.setText(validTtft > 0 ? String.valueOf(minTtft) : "-");
        maxTtftLabel.setText(validTtft > 0 ? String.valueOf(maxTtft) : "-");
        avgTtfbLabel.setText(validTtfb > 0 ? String.format("%.1f", sumTtfb / (double) validTtfb) : "-");
        avgTpotLabel.setText(validTpot > 0 ? String.format("%.2f", sumTpot / validTpot) : "-");
        avgTpsLabel.setText(validTps > 0 ? String.format("%.2f", sumTps / validTps) : "-");
        avgTotalRtLabel.setText(validTotalRt > 0 ? String.format("%.1f", sumTotalRt / (double) validTotalRt) : "-");
        totalInputTokensLabel.setText(String.valueOf(totalIn));
        totalOutputTokensLabel.setText(String.valueOf(totalOut));
    }

    /**
     * 清除所有统计数据和日志。
     */
    @Override
    public void clearData() {
        sampleCount = 0;
        sumTtft = sumTtfb = sumTotalRt = 0;
        sumTpot = sumTps = 0;
        totalIn = totalOut = 0;
        validTtft = validTtfb = validTpot = validTps = validTotalRt = 0;
        minTtft = Long.MAX_VALUE;
        maxTtft = Long.MIN_VALUE;

        logModel.clear();
        countLabel.setText("0");
        avgTtftLabel.setText("-");
        minTtftLabel.setText("-");
        maxTtftLabel.setText("-");
        avgTtfbLabel.setText("-");
        avgTpotLabel.setText("-");
        avgTpsLabel.setText("-");
        avgTotalRtLabel.setText("-");
        totalInputTokensLabel.setText("0");
        totalOutputTokensLabel.setText("0");
    }

    /**
     * 配置测试元素，将监听器绑定到 ResultCollector。
     *
     * @param element 测试元素
     */
    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);
        ResultCollector collector = (ResultCollector) element;
        collector.setListener(this);
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
    }

    /**
     * 返回监听器的显示名称资源键。
     *
     * @return 显示名称
     */
    @Override
    public String getLabelResource() {
        return "SSE-LLM Metrics Listener";
    }

    /**
     * 返回监听器的静态显示名称。
     *
     * @return 显示名称
     */
    @Override
    public String getStaticLabel() {
        return "SSE-LLM Metrics Listener";
    }
}
