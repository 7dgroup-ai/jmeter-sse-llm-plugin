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
    /** 表格最大显示条数 */
    private static final int MAX_LOG_ENTRIES = 500;
    /** 表格列名 */
    private static final String[] COLUMN_NAMES = {
            "#", "Timestamp", "TTFT(ms)", "TTFB(ms)", "TPOT(ms/t)", "Token/s",
            "TotalRT(ms)", "In", "Out", "Chunks"
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

        add(statsPanel, BorderLayout.NORTH);

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
        table.getColumnModel().getColumn(6).setPreferredWidth(80);   // TotalRT
        table.getColumnModel().getColumn(7).setPreferredWidth(50);   // In
        table.getColumnModel().getColumn(8).setPreferredWidth(50);   // Out
        table.getColumnModel().getColumn(9).setPreferredWidth(60);   // Chunks

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
        String data = result.getResponseDataAsString();
        if (data == null || data.isEmpty()) return;

        try {
            SseMetrics metrics = gson.fromJson(data, SseMetrics.class);
            if (metrics == null) {
                addError("null response");
                return;
            }

            sampleCount++;

            long ttft = metrics.getTTFT();
            long ttfb = metrics.getTTFB();
            double tpot = metrics.getTPOT();
            double tps = metrics.getTokenPerSec();
            long totalRt = metrics.getTotalRT();

            // 累加有效指标（>= 0 表示有有效数据）
            if (ttft >= 0) {
                sumTtft += ttft;
                validTtft++;
                minTtft = Math.min(minTtft, ttft);
                maxTtft = Math.max(maxTtft, ttft);
            }
            if (ttfb >= 0) { sumTtfb += ttfb; validTtfb++; }
            if (tpot >= 0) { sumTpot += tpot; validTpot++; }
            if (tps >= 0) { sumTps += tps; validTps++; }
            if (totalRt >= 0) { sumTotalRt += totalRt; validTotalRt++; }
            totalIn += metrics.inputTokens;
            totalOut += metrics.outputTokens;

            // 构建表格行数据
            String timestamp = sdf.format(new Date());
            Object[] row = {
                    sampleCount,
                    timestamp,
                    ttft >= 0 ? ttft : "-",
                    ttfb >= 0 ? ttfb : "-",
                    tpot >= 0 ? String.format("%.2f", tpot) : "-",
                    tps >= 0 ? String.format("%.2f", tps) : "-",
                    totalRt >= 0 ? totalRt : "-",
                    metrics.inputTokens,
                    metrics.outputTokens,
                    metrics.tokenCount
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
            addError(e.getMessage());
        }
    }

    /**
     * 记录解析错误并更新错误计数。
     *
     * @param detail 错误详情
     */
    private void addError(String detail) {
        errorCount++;
        SwingUtilities.invokeLater(() -> {
            errorLabel.setText(String.valueOf(errorCount));
            errorLabel.setForeground(Color.RED);
        });
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
    public void clearData() {
        sampleCount = 0;
        errorCount = 0;
        sumTtft = sumTtfb = sumTotalRt = 0;
        sumTpot = sumTps = 0;
        totalIn = totalOut = 0;
        validTtft = validTtfb = validTpot = validTps = validTotalRt = 0;
        minTtft = Long.MAX_VALUE;
        maxTtft = Long.MIN_VALUE;

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
