package com.llm.perf.sse;

import com.google.gson.JsonParser;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * SSE Stream Sampler GUI 面板。
 *
 * <p>Headers 使用 Name/Value 表格编辑，支持从浏览器/cURL 直接粘贴，
 * 属性存储格式保持 {@code HeaderName:HeaderValue}（每行一个）以兼容已有脚本。</p>
 */
public class SseStreamSamplerGui extends AbstractSamplerGui {
    private JTextField tfUrl;
    private JTextArea taBody;
    private JTable headerTable;
    private DefaultTableModel headerTableModel;
    private JTextField tfConnectTimeout;
    private JTextField tfReadTimeout;
    private JComboBox<String> cbApiType;
    private JLabel lblUrlError;
    private JLabel lblBodyError;
    private JLabel lblTimeoutError;

    public SseStreamSamplerGui() {
        init();
    }

    // ─── Headers 表格工具方法 ───────────────────────────────────────

    /** 从表格模型构建 "Key:Value\n..." 格式文本，供属性存储。 */
    private String headersToText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < headerTableModel.getRowCount(); i++) {
            String name = String.valueOf(headerTableModel.getValueAt(i, 0)).trim();
            String value = String.valueOf(headerTableModel.getValueAt(i, 1)).trim();
            if (!name.isEmpty()) {
                sb.append(name).append(": ").append(value).append("\n");
            }
        }
        return sb.toString();
    }

    /** 将 "Key:Value\n..." 格式文本解析到表格模型，兼容旧格式。 */
    private void textToHeaders(String text) {
        headerTableModel.setRowCount(0);
        if (text == null || text.trim().isEmpty()) return;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String name = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            headerTableModel.addRow(new Object[]{name, value});
        }
    }

    /** 从剪切板读取文本并解析到表格（支持浏览器/cURL 格式）。 */
    private void pasteFromClipboard() {
        try {
            java.awt.datatransfer.Clipboard clipboard =
                    Toolkit.getDefaultToolkit().getSystemClipboard();
            String text = (String) clipboard.getData(DataFlavor.stringFlavor);
            if (text == null || text.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "剪切板为空", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            parseAndAddHeaders(text);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "读取剪切板失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * 解析多种格式的 header 文本并追加到表格。
     *
     * <p>支持格式：</p>
     * <ul>
     *   <li>标准：{@code Key: Value}（冒号分隔）</li>
     *   <li>cURL：{@code -H 'Key: Value'}</li>
     *   <li>浏览器开发者工具复制格式（可能带 tab 分隔）</li>
     *   <li>空行或无冒号行自动跳过</li>
     * </ul>
     */
    private void parseAndAddHeaders(String text) {
        int added = 0;
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 去掉 cURL -H '...' 或 -H "..." 包裹
            if (trimmed.startsWith("-H")) {
                trimmed = trimmed.substring(2).trim();
            }
            if ((trimmed.startsWith("'") && trimmed.endsWith("'"))
                    || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }

            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String name = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (name.isEmpty()) continue;

            headerTableModel.addRow(new Object[]{name, value});
            added++;
        }
        if (added > 0) {
            JOptionPane.showMessageDialog(this,
                    "已添加 " + added + " 个请求头", "提示", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this,
                    "未识别到有效的 Header（格式: Key: Value）", "提示", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ─── GUI 初始化 ─────────────────────────────────────────────────

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        VerticalPanel mainPanel = new VerticalPanel();

        // URL
        JPanel urlPanel = new JPanel(new BorderLayout());
        tfUrl = new JTextField();
        lblUrlError = new JLabel(" ");
        lblUrlError.setForeground(Color.RED);
        lblUrlError.setFont(lblUrlError.getFont().deriveFont(Font.PLAIN, 11f));
        urlPanel.add(lblUrlError, BorderLayout.NORTH);
        urlPanel.add(createLabelPanel("SSE Url (*必填):", tfUrl), BorderLayout.CENTER);
        mainPanel.add(urlPanel);

        // API 类型
        cbApiType = new JComboBox<>(new String[]{"openai", "dify", "claude", "gemini"});
        cbApiType.setSelectedItem("openai");
        mainPanel.add(createLabelPanel("API Type (*必填):", cbApiType));

        // Headers 表格
        mainPanel.add(buildHeaderPanel());

        // Request Body
        JPanel bodyPanel = new JPanel(new BorderLayout());
        taBody = new JTextArea(8, 60);
        lblBodyError = new JLabel(" ");
        lblBodyError.setForeground(Color.RED);
        lblBodyError.setFont(lblBodyError.getFont().deriveFont(Font.PLAIN, 11f));
        bodyPanel.add(lblBodyError, BorderLayout.NORTH);
        bodyPanel.add(createLabelPanel("Request Body JSON (*必填):", new JScrollPane(taBody)), BorderLayout.CENTER);
        mainPanel.add(bodyPanel);

        // 超时设置
        JPanel timePanel = new JPanel(new GridLayout(1, 2));
        tfConnectTimeout = new JTextField("10000");
        tfReadTimeout = new JTextField("60000");
        lblTimeoutError = new JLabel(" ");
        lblTimeoutError.setForeground(Color.RED);
        lblTimeoutError.setFont(lblTimeoutError.getFont().deriveFont(Font.PLAIN, 11f));
        timePanel.add(createLabelPanel("ConnectTimeout(ms) (*必填)", tfConnectTimeout));
        timePanel.add(createLabelPanel("ReadTimeout(ms) (*必填)", tfReadTimeout));
        mainPanel.add(timePanel);
        mainPanel.add(lblTimeoutError);

        addFocusListeners();
        add(mainPanel, BorderLayout.CENTER);
    }

    /** 构建 Headers 表格 + 工具栏面板。 */
    private JPanel buildHeaderPanel() {
        headerTableModel = new DefaultTableModel(new String[]{"Name", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return true; }
        };
        headerTable = new JTable(headerTableModel);
        headerTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        headerTable.setRowHeight(22);
        headerTable.getTableHeader().setReorderingAllowed(false);
        headerTable.getTableHeader().setFont(new Font("Dialog", Font.BOLD, 12));
        headerTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        headerTable.getColumnModel().getColumn(1).setPreferredWidth(400);

        // 工具栏按钮
        JButton btnAdd = new JButton("Add");
        btnAdd.setMnemonic('A');
        btnAdd.addActionListener(e -> headerTableModel.addRow(new Object[]{"", ""}));

        JButton btnRemove = new JButton("Remove");
        btnRemove.setMnemonic('R');
        btnRemove.addActionListener(e -> {
            int[] rows = headerTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                headerTableModel.removeRow(rows[i]);
            }
        });

        JButton btnClear = new JButton("Clear");
        btnClear.addActionListener(e -> headerTableModel.setRowCount(0));

        JButton btnPaste = new JButton("Paste from Clipboard");
        btnPaste.setMnemonic('P');
        btnPaste.setToolTipText("从剪切板粘贴 Header（支持浏览器/cURL 格式）");
        btnPaste.addActionListener(e -> pasteFromClipboard());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        toolbar.add(btnAdd);
        toolbar.add(btnRemove);
        toolbar.add(btnClear);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(btnPaste);

        JPanel headerPanel = new JPanel(new BorderLayout());
        JLabel lbl = new JLabel("Headers (Name / Value):");
        lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
        headerPanel.add(lbl, BorderLayout.NORTH);
        headerPanel.add(toolbar, BorderLayout.CENTER);
        headerPanel.add(new JScrollPane(headerTable), BorderLayout.SOUTH);
        return headerPanel;
    }

    // ─── 验证 ───────────────────────────────────────────────────────

    private void addFocusListeners() {
        tfUrl.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { validateUrl(); }
        });
        taBody.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { validateBody(); }
        });
        tfConnectTimeout.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { validateTimeouts(); }
        });
        tfReadTimeout.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { validateTimeouts(); }
        });
    }

    private boolean validateUrl() {
        String url = tfUrl.getText().trim();
        if (url.isEmpty()) { lblUrlError.setText("URL不能为空"); return false; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            lblUrlError.setText("URL必须以http://或https://开头"); return false;
        }
        lblUrlError.setText(" ");
        return true;
    }

    private boolean validateBody() {
        String body = taBody.getText().trim();
        if (body.isEmpty()) { lblBodyError.setText("请求体不能为空"); return false; }
        try { JsonParser.parseString(body).getAsJsonObject(); }
        catch (Exception e) { lblBodyError.setText("JSON格式错误: " + e.getMessage()); return false; }
        lblBodyError.setText(" ");
        return true;
    }

    private boolean validateTimeouts() {
        String ct = tfConnectTimeout.getText().trim();
        String rt = tfReadTimeout.getText().trim();
        if (ct.isEmpty() || rt.isEmpty()) { lblTimeoutError.setText("超时时间不能为空"); return false; }
        try {
            int c = Integer.parseInt(ct);
            int r = Integer.parseInt(rt);
            if (c <= 0 || r <= 0) { lblTimeoutError.setText("超时时间必须为正整数"); return false; }
        } catch (NumberFormatException e) { lblTimeoutError.setText("超时时间必须为数字"); return false; }
        lblTimeoutError.setText(" ");
        return true;
    }

    public boolean validateAll() {
        return validateUrl() && validateBody() && validateTimeouts();
    }

    // ─── 工具方法 ───────────────────────────────────────────────────

    private JPanel createLabelPanel(String label, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel(label), BorderLayout.NORTH);
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

    // ─── TestElement 绑定 ───────────────────────────────────────────

    @Override
    public TestElement createTestElement() {
        SseStreamSampler sampler = new SseStreamSampler();
        modifyTestElement(sampler);
        return sampler;
    }

    @Override
    public void modifyTestElement(TestElement testElement) {
        super.configureTestElement(testElement);
        SseStreamSampler sampler = (SseStreamSampler) testElement;
        sampler.setProperty(SseStreamSampler.URL, tfUrl.getText().trim());
        sampler.setProperty(SseStreamSampler.REQUEST_BODY, taBody.getText());
        sampler.setProperty(SseStreamSampler.HEADERS, headersToText());
        sampler.setProperty(SseStreamSampler.CONNECT_TIMEOUT, tfConnectTimeout.getText().trim());
        sampler.setProperty(SseStreamSampler.READ_TIMEOUT, tfReadTimeout.getText().trim());
        sampler.setProperty(SseStreamSampler.API_TYPE, cbApiType.getSelectedItem().toString());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        SseStreamSampler sampler = (SseStreamSampler) element;
        tfUrl.setText(sampler.getPropertyAsString(SseStreamSampler.URL));
        taBody.setText(sampler.getPropertyAsString(SseStreamSampler.REQUEST_BODY));
        textToHeaders(sampler.getPropertyAsString(SseStreamSampler.HEADERS));
        tfConnectTimeout.setText(sampler.getPropertyAsString(SseStreamSampler.CONNECT_TIMEOUT));
        tfReadTimeout.setText(sampler.getPropertyAsString(SseStreamSampler.READ_TIMEOUT));
        String apiType = sampler.getPropertyAsString(SseStreamSampler.API_TYPE, "openai");
        cbApiType.setSelectedItem(apiType);
    }

    @Override
    public String getLabelResource() { return "SSE-LLM Stream Sampler"; }

    @Override
    public String getStaticLabel() { return "SSE-LLM Stream Sampler"; }
}
