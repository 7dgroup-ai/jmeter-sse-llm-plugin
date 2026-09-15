package com.llm.perf.sse;

import com.google.gson.Gson;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractVisualizer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableModel;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;

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
    /** 表格最大显示条数 */
    private static final int MAX_LOG_ENTRIES = 500;
    /** 分位标签刷新频率：每 N 个样本重算一次，避免每次排序开销 */
    private static final int PERCENTILE_REFRESH_INTERVAL = 50;
    /** 分位数水库采样容量：限制内存与排序开销，超出后近似分位 */
    private static final int MAX_PERCENTILE_SAMPLES = 20000;
    /** 表格列名 */
    private static final String[] COLUMN_NAMES = {
            "#", "Timestamp", "TTFT(ms)", "TTFB(ms)", "TPOT(ms/t)", "Token/s", "RealTok/s",
            "TotalRT(ms)", "TTFT-TTFB(ms)", "In", "Out", "Chunks", "MaxGap(ms)", "Stall"
    };

    /** 采样计数 */
    private long sampleCount = 0;
    /** 解析失败计数 */
    private long errorCount = 0;
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
    /** TTFT/TTFB/TotalRT/首字节-首token 间隔原始样本值（水库采样，用于分位数） */
    private final PercentileReservoir ttftSamples = new PercentileReservoir(MAX_PERCENTILE_SAMPLES);
    private final PercentileReservoir ttfbSamples = new PercentileReservoir(MAX_PERCENTILE_SAMPLES);
    private final PercentileReservoir totalRtSamples = new PercentileReservoir(MAX_PERCENTILE_SAMPLES);
    private final PercentileReservoir ttftTtfbGapSamples = new PercentileReservoir(MAX_PERCENTILE_SAMPLES);
    /** TTFT/TotalRT 平方和（用于标准差） */
    private double sumSqTtft = 0, sumSqTotalRt = 0;
    /** 所有样本中最大 token 间隔与卡顿总数 */
    private long maxMaxGap = 0;
    private long stallTotal = 0;

    /** 表格数据模型 */
    private DefaultTableModel tableModel;
    /** 表格组件 */
    private JTable table;
    /** 统计标签组件 */
    private JLabel countLabel;
    private JLabel avgTtftLabel, minTtftLabel, maxTtftLabel;
    private JLabel avgTtfbLabel;
    private JLabel avgTpotLabel;
    private JLabel avgTpsLabel;
    private JLabel avgTotalRtLabel;
    private JLabel totalInputTokensLabel, totalOutputTokensLabel;
    private JLabel errorLabel;
    /** 分位/流式指标标签 */
    private JLabel errorRateLabel, tp95TtftLabel, tp95TotalRtLabel;
    private JLabel realTpsLabel, maxGapLabel, stallLabel;
    /** JSON 反序列化器 */
    private final Gson gson = new Gson();
    /** 时间格式化器 */
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    /**
     * 分位数水库采样器。
     *
     * <p>将原始样本限制在固定容量内，超出后以水库算法等概率替换旧样本，
     * 保证录入分位数统计的内存与排序开销有界（近似估计，样本量足够时误差可忽略）。</p>
     */
    private static class PercentileReservoir {
        private final int capacity;
        private final List<Long> values;
        private final Random random = new Random();
        private long seen = 0;

        PercentileReservoir(int capacity) {
            this.capacity = capacity;
            this.values = new ArrayList<>(capacity);
        }

        synchronized void add(long value) {
            seen++;
            if (values.size() < capacity) {
                values.add(value);
            } else if (random.nextDouble() < (double) capacity / seen) {
                values.set(random.nextInt(capacity), value);
            }
        }

        synchronized List<Long> snapshot() {
            return new ArrayList<>(values);
        }

        synchronized boolean isEmpty() {
            return values.isEmpty();
        }

        synchronized void clear() {
            values.clear();
            seen = 0;
        }
    }

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
        errorLabel = new JLabel("0");

        statsPanel.add(createStatItem("Samples:", countLabel));
        statsPanel.add(Box.createHorizontalStrut(10));
        statsPanel.add(createStatItem("Errors:", errorLabel));
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

        // 聚合统计面板支持右键复制统计结果
        attachStatsPopup(statsPanel);

        // 分位/流式指标面板
        JPanel statsPanel2 = new JPanel();
        statsPanel2.setLayout(new BoxLayout(statsPanel2, BoxLayout.X_AXIS));
        statsPanel2.setBorder(BorderFactory.createTitledBorder("Percentiles & Streaming"));

        errorRateLabel = new JLabel("0%");
        tp95TtftLabel = new JLabel("-");
        tp95TotalRtLabel = new JLabel("-");
        realTpsLabel = new JLabel("-");
        maxGapLabel = new JLabel("-");
        stallLabel = new JLabel("0");

        statsPanel2.add(createStatItem("Error%:", errorRateLabel));
        statsPanel2.add(Box.createHorizontalStrut(10));
        statsPanel2.add(createStatItem("TTFT P95(ms):", tp95TtftLabel));
        statsPanel2.add(Box.createHorizontalStrut(10));
        statsPanel2.add(createStatItem("TotalRT P95(ms):", tp95TotalRtLabel));
        statsPanel2.add(Box.createHorizontalStrut(10));
        statsPanel2.add(createStatItem("RealTok/s:", realTpsLabel));
        statsPanel2.add(Box.createHorizontalStrut(10));
        statsPanel2.add(createStatItem("MaxGap(ms):", maxGapLabel));
        statsPanel2.add(Box.createHorizontalStrut(10));
        statsPanel2.add(createStatItem("Stall>1s:", stallLabel));

        // 分位面板支持右键复制统计结果
        attachStatsPopup(statsPanel2);

        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));
        northPanel.add(statsPanel);
        northPanel.add(statsPanel2);

        add(northPanel, BorderLayout.NORTH);

        // 样本表格面板
        tableModel = new DefaultTableModel(COLUMN_NAMES, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setFont(new Font("Monospaced", Font.PLAIN, 12));
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(new Font("Dialog", Font.BOLD, 12));

        // 设置列宽
        table.getColumnModel().getColumn(0).setPreferredWidth(40);   // #
        table.getColumnModel().getColumn(1).setPreferredWidth(100);  // Timestamp
        table.getColumnModel().getColumn(2).setPreferredWidth(70);   // TTFT
        table.getColumnModel().getColumn(3).setPreferredWidth(70);   // TTFB
        table.getColumnModel().getColumn(4).setPreferredWidth(80);   // TPOT
        table.getColumnModel().getColumn(5).setPreferredWidth(70);   // Token/s
        table.getColumnModel().getColumn(6).setPreferredWidth(80);   // RealTok/s
        table.getColumnModel().getColumn(7).setPreferredWidth(80);   // TotalRT
        table.getColumnModel().getColumn(8).setPreferredWidth(80);   // TTFT-TTFB
        table.getColumnModel().getColumn(9).setPreferredWidth(50);   // In
        table.getColumnModel().getColumn(10).setPreferredWidth(50);  // Out
        table.getColumnModel().getColumn(11).setPreferredWidth(60);  // Chunks
        table.getColumnModel().getColumn(12).setPreferredWidth(80);  // MaxGap
        table.getColumnModel().getColumn(13).setPreferredWidth(50);  // Stall

        // 右键菜单
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handlePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handlePopup(e);
            }

            private void handlePopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    JPopupMenu popup = new JPopupMenu();

                    JMenuItem copySelected = new JMenuItem("Copy Selected (TSV)");
                    copySelected.addActionListener(a -> copyToClipboard(false));
                    popup.add(copySelected);

                    JMenuItem copyAll = new JMenuItem("Copy All (TSV)");
                    copyAll.addActionListener(a -> copyToClipboard(true));
                    popup.add(copyAll);

                    popup.addSeparator();
                    addStatsCopyItems(popup);
                    popup.addSeparator();

                    JMenuItem clearAll = new JMenuItem("Clear All");
                    clearAll.addActionListener(a -> clearData());
                    popup.add(clearAll);

                    popup.show(table, e.getX(), e.getY());
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Sample Results (last " + MAX_LOG_ENTRIES + ")"));
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
    public synchronized void add(SampleResult result) {
        // 所有收到的结果都计入样本数与错误数（含校验失败、空 data 的样本），保证 Error% 口径一致
        sampleCount++;
        boolean failed = !result.isSuccessful();
        if (failed) {
            errorCount++;
        }

        String data = result.getResponseDataAsString();
        if (data == null || data.isEmpty()) {
            SwingUtilities.invokeLater(this::updateStatsLabels);
            return;
        }

        try {
            SseMetrics metrics = gson.fromJson(data, SseMetrics.class);
            if (metrics == null) {
                // 仅成功样本的解析失败才额外计错，避免与 failed 样本重复计数
                if (!failed) {
                    errorCount++;
                }
                SwingUtilities.invokeLater(this::updateStatsLabels);
                return;
            }

            long ttft = metrics.getTTFT();
            long ttfb = metrics.getTTFB();
            double tpot = metrics.getTPOT();
            double tps = metrics.getTokenPerSec();
            double realTps = metrics.getRealTokenPerSec();
            long totalRt = metrics.getTotalRT();

            // 失败样本仅展示（表格行），不计入性能统计（其时间/指标无性能意义）
            if (!failed) {
                // 累加有效指标（>= 0 表示有有效数据）
                if (ttft >= 0) {
                    sumTtft += ttft;
                    sumSqTtft += (double) ttft * ttft;
                    validTtft++;
                    minTtft = Math.min(minTtft, ttft);
                    maxTtft = Math.max(maxTtft, ttft);
                    ttftSamples.add(ttft);
                }
                if (ttfb >= 0) { sumTtfb += ttfb; validTtfb++; ttfbSamples.add(ttfb); }
                if (tpot >= 0) { sumTpot += tpot; validTpot++; }
                if (tps >= 0) { sumTps += tps; validTps++; }
                if (totalRt >= 0) { sumTotalRt += totalRt; sumSqTotalRt += (double) totalRt * totalRt; validTotalRt++; totalRtSamples.add(totalRt); }
                if (ttft >= 0 && ttfb >= 0) {
                    ttftTtfbGapSamples.add(ttft - ttfb);
                }
                totalIn += metrics.inputTokens;
                totalOut += metrics.outputTokens;
                maxMaxGap = Math.max(maxMaxGap, metrics.maxTokenGap);
                stallTotal += metrics.stallCount;
            }

            // 构建表格行数据
            String timestamp = sdf.format(new Date());
            Object[] row = {
                    sampleCount,
                    timestamp,
                    ttft >= 0 ? ttft : "-",
                    ttfb >= 0 ? ttfb : "-",
                    tpot >= 0 ? String.format("%.2f", tpot) : "-",
                    tps >= 0 ? String.format("%.2f", tps) : "-",
                    realTps >= 0 ? String.format("%.2f", realTps) : "-",
                    totalRt >= 0 ? totalRt : "-",
                    ttft >= 0 && ttfb >= 0 ? (ttft - ttfb) : "-",
                    metrics.inputTokens,
                    metrics.outputTokens,
                    metrics.tokenCount,
                    metrics.maxTokenGap,
                    metrics.stallCount
            };

            // 在 EDT 线程中更新 GUI
            SwingUtilities.invokeLater(() -> {
                // 保持表格条数不超过上限，超过时移除最早的条目
                while (tableModel.getRowCount() >= MAX_LOG_ENTRIES) {
                    tableModel.removeRow(0);
                }
                tableModel.addRow(row);
                updateStatsLabels();
            });
        } catch (Exception e) {
            // 仅成功样本的解析失败才额外计错，避免与 failed 样本重复计数
            if (!failed) {
                errorCount++;
            }
            SwingUtilities.invokeLater(() -> {
                errorLabel.setText(String.valueOf(errorCount));
                errorLabel.setForeground(Color.RED);
            });
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
     *
     * <p>求和类指标每次更新；分位类指标按 {@link #PERCENTILE_REFRESH_INTERVAL}
     * 节流重算，避免高频排序开销。</p>
     */
    private synchronized void updateStatsLabels() {
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

        errorRateLabel.setText(sampleCount > 0 ? String.format("%.1f%%", errorCount * 100.0 / sampleCount) : "0%");
        realTpsLabel.setText(sumTotalRt > 0 && totalOut > 0 ? String.format("%.1f", totalOut / (double) sumTotalRt * 1000) : "-");
        maxGapLabel.setText(String.valueOf(maxMaxGap));
        stallLabel.setText(String.valueOf(stallTotal));

        // 分位标签节流重算：低样本量（<= 区间）时每次更新，之后每满一个区间更新一次
        if (sampleCount <= PERCENTILE_REFRESH_INTERVAL || sampleCount % PERCENTILE_REFRESH_INTERVAL == 0) {
            tp95TtftLabel.setText(formatMillis(percentile(ttftSamples.snapshot(), 95)));
            tp95TotalRtLabel.setText(formatMillis(percentile(totalRtSamples.snapshot(), 95)));
        }
    }

    /**
     * 计算分位数（线性插值法）。
     *
     * @param values 样本副本（可排序）
     * @param p 百分位（如 50、90、95、99）
     * @return 分位值，无数据返回 -1
     */
    private long percentile(List<Long> values, double p) {
        if (values == null || values.isEmpty()) {
            return -1;
        }
        values.sort(null);
        return percentileSorted(values, p);
    }

    /**
     * 计算已排序样本的分位数（线性插值法）。
     *
     * <p>调用方需保证 {@code sorted} 已升序排列，避免对同一份样本重复排序。</p>
     *
     * @param sorted 已升序排序的样本列表
     * @param p 百分位（如 50、90、95、99）
     * @return 分位值，无数据返回 -1
     */
    private long percentileSorted(List<Long> sorted, double p) {
        int n = sorted.size();
        if (n == 0) {
            return -1;
        }
        if (n == 1) {
            return sorted.get(0);
        }
        double pos = (p / 100.0) * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        long vLo = sorted.get(lo);
        if (lo == hi) {
            return vLo;
        }
        long vHi = sorted.get(hi);
        return Math.round(vLo + (vHi - vLo) * (pos - lo));
    }

    /**
     * 计算标准差。
     *
     * @param sumSq 样本平方和
     * @param sum 样本总和
     * @param n 样本数量
     * @return 标准差，n 不足 2 返回 -1
     */
    private double stdDev(double sumSq, double sum, int n) {
        if (n < 2 || sum <= 0) {
            return -1d;
        }
        double mean = sum / n;
        double variance = sumSq / n - mean * mean;
        return Math.sqrt(Math.max(0, variance));
    }

    /**
     * 格式化为毫秒字符串，-1 显示 "-"。
     *
     * @param value 毫秒值
     * @return 格式化字符串
     */
    private String formatMillis(long value) {
        return value >= 0 ? String.valueOf(value) : "-";
    }

    /**
     * 将表格数据复制到系统剪贴板（TSV 格式，可粘贴到 Excel）。
     *
     * @param all true=复制全部, false=仅复制选中行
     */
    private void copyToClipboard(boolean all) {
        StringBuilder sb = new StringBuilder();
        // 写入表头
        for (int i = 0; i < tableModel.getColumnCount(); i++) {
            if (i > 0) sb.append("\t");
            sb.append(tableModel.getColumnName(i));
        }
        sb.append("\n");

        // 写入数据行
        if (all) {
            for (int row = 0; row < tableModel.getRowCount(); row++) {
                appendRow(sb, row);
            }
        } else {
            int[] selectedRows = table.getSelectedRows();
            for (int row : selectedRows) {
                appendRow(sb, row);
            }
        }

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(sb.toString()), null);
    }

    /**
     * 将聚合统计（Aggregate Statistics）以 TSV 格式复制到剪贴板。
     *
     * <p>输出表头一行 + 数值一行（TSV），包含平均值、分位数（P50/P90/P95/P99）、
     * 标准差、错误率、真实 Token/s 及流式卡顿数据，可直接粘贴到 Excel。
     * 配合 "复制后清空" 使用，多次运行时每次导出本行，逐次拼接即可生成报告。</p>
     */
    private synchronized void copyStatsToClipboard() {
        List<Long> ttftSnap = ttftSamples.snapshot();
        List<Long> ttfbSnap = ttfbSamples.snapshot();
        List<Long> totalRtSnap = totalRtSamples.snapshot();
        List<Long> gapSnap = ttftTtfbGapSamples.snapshot();
        // 各分位查询复用同一份已排序样本，避免重复排序
        ttftSnap.sort(null);
        ttfbSnap.sort(null);
        totalRtSnap.sort(null);

        String header = "Samples\tErrors\tError%\t"
                + "TTFT avg(ms)\tTTFT p50\tp90\tp95\tp99\tTTFT StdDev\t"
                + "TTFB avg(ms)\tTTFB p50\tp90\tp95\tp99\t"
                + "TTFT-TTFB avg(ms)\t"
                + "TotalRT avg(ms)\tTotalRT p50\tp90\tp95\tp99\tTotalRT StdDev\t"
                + "TPOT avg(ms/t)\tToken/s\tRealToken/s\t"
                + "MaxGap(ms)\tStall>1s\tIn\tOut\n";

        StringBuilder sb = new StringBuilder(header);
        sb.append(sampleCount).append('\t')
                .append(errorCount).append('\t')
                .append(sampleCount > 0 ? String.format("%.2f", errorCount * 100.0 / sampleCount) : "0").append('\t')
                .append(validTtft > 0 ? String.format("%.1f", sumTtft / (double) validTtft) : "-").append('\t')
                .append(formatMillis(percentileSorted(ttftSnap, 50))).append('\t')
                .append(formatMillis(percentileSorted(ttftSnap, 90))).append('\t')
                .append(formatMillis(percentileSorted(ttftSnap, 95))).append('\t')
                .append(formatMillis(percentileSorted(ttftSnap, 99))).append('\t')
                .append(validTtft > 0 ? String.format("%.1f", stdDev(sumSqTtft, sumTtft, validTtft)) : "-").append('\t')
                .append(validTtfb > 0 ? String.format("%.1f", sumTtfb / (double) validTtfb) : "-").append('\t')
                .append(formatMillis(percentileSorted(ttfbSnap, 50))).append('\t')
                .append(formatMillis(percentileSorted(ttfbSnap, 90))).append('\t')
                .append(formatMillis(percentileSorted(ttfbSnap, 95))).append('\t')
                .append(formatMillis(percentileSorted(ttfbSnap, 99))).append('\t')
                .append(gapSnap.isEmpty() ? "-" : String.format("%.1f", avg(gapSnap))).append('\t')
                .append(validTotalRt > 0 ? String.format("%.1f", sumTotalRt / (double) validTotalRt) : "-").append('\t')
                .append(formatMillis(percentileSorted(totalRtSnap, 50))).append('\t')
                .append(formatMillis(percentileSorted(totalRtSnap, 90))).append('\t')
                .append(formatMillis(percentileSorted(totalRtSnap, 95))).append('\t')
                .append(formatMillis(percentileSorted(totalRtSnap, 99))).append('\t')
                .append(validTotalRt > 0 ? String.format("%.1f", stdDev(sumSqTotalRt, sumTotalRt, validTotalRt)) : "-").append('\t')
                .append(validTpot > 0 ? String.format("%.2f", sumTpot / validTpot) : "-").append('\t')
                .append(validTps > 0 ? String.format("%.2f", sumTps / validTps) : "-").append('\t')
                .append(sumTotalRt > 0 && totalOut > 0 ? String.format("%.1f", totalOut / (double) sumTotalRt * 1000) : "-").append('\t')
                .append(maxMaxGap).append('\t')
                .append(stallTotal).append('\t')
                .append(totalIn).append('\t')
                .append(totalOut).append('\n');

        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(sb.toString()), null);
    }

    /**
     * 计算长整型列表平均值。
     *
     * @param values 数值列表
     * @return 平均值
     */
    private double avg(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return -1;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.size();
    }

    /**
     * 向右键菜单添加聚合统计复制项。
     *
     * @param popup 目标右键菜单
     */
    private void addStatsCopyItems(JPopupMenu popup) {
        JMenuItem copyStats = new JMenuItem("Copy Aggregate Statistics (TSV)");
        copyStats.addActionListener(a -> copyStatsToClipboard());
        popup.add(copyStats);

        JMenuItem copyStatsAndClear = new JMenuItem("Copy Aggregate Statistics & Clear");
        copyStatsAndClear.addActionListener(a -> {
            copyStatsToClipboard();
            clearData();
        });
        popup.add(copyStatsAndClear);
    }

    /**
     * 创建聚合统计面板的右键菜单。
     *
     * @return 聚合统计右键菜单
     */
    private JPopupMenu makeStatsPopup() {
        JPopupMenu popup = new JPopupMenu();
        addStatsCopyItems(popup);
        return popup;
    }

    /**
     * 为组件及其所有子组件递归绑定聚合统计右键菜单。
     *
     * <p>鼠标事件不会自动传播到父面板，因此需要递归绑定到每个子组件。</p>
     *
     * @param comp 目标组件
     */
    private void attachStatsPopup(JComponent comp) {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showStatsPopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showStatsPopup(e);
            }

            private void showStatsPopup(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    makeStatsPopup().show(comp, e.getX(), e.getY());
                }
            }
        };
        comp.addMouseListener(adapter);
        for (Component c : comp.getComponents()) {
            if (c instanceof JComponent) {
                attachStatsPopup((JComponent) c);
            }
        }
    }

    /**
     * 将指定行的数据追加到 StringBuilder。
     *
     * @param sb 目标 StringBuilder
     * @param row 行索引
     */
    private void appendRow(StringBuilder sb, int row) {
        for (int col = 0; col < tableModel.getColumnCount(); col++) {
            if (col > 0) sb.append("\t");
            Object value = tableModel.getValueAt(row, col);
            sb.append(value != null ? value.toString() : "");
        }
        sb.append("\n");
    }

    /**
     * 清除所有统计数据和日志。
     */
    @Override
    public synchronized void clearData() {
        sampleCount = 0;
        errorCount = 0;
        sumTtft = sumTtfb = sumTotalRt = 0;
        sumSqTtft = sumSqTotalRt = 0;
        sumTpot = sumTps = 0;
        totalIn = totalOut = 0;
        validTtft = validTtfb = validTpot = validTps = validTotalRt = 0;
        minTtft = Long.MAX_VALUE;
        maxTtft = Long.MIN_VALUE;
        maxMaxGap = 0;
        stallTotal = 0;
        ttftSamples.clear();
        ttfbSamples.clear();
        totalRtSamples.clear();
        ttftTtfbGapSamples.clear();

        SwingUtilities.invokeLater(() -> {
            tableModel.setRowCount(0);
            countLabel.setText("0");
            errorLabel.setText("0");
            errorLabel.setForeground(Color.BLACK);
            avgTtftLabel.setText("-");
            minTtftLabel.setText("-");
            maxTtftLabel.setText("-");
            avgTtfbLabel.setText("-");
            avgTpotLabel.setText("-");
            avgTpsLabel.setText("-");
            avgTotalRtLabel.setText("-");
            totalInputTokensLabel.setText("0");
            totalOutputTokensLabel.setText("0");
            errorRateLabel.setText("0%");
            tp95TtftLabel.setText("-");
            tp95TotalRtLabel.setText("-");
            realTpsLabel.setText("-");
            maxGapLabel.setText("-");
            stallLabel.setText("0");
        });
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
