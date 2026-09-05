package com.llm.perf.sse;

import com.google.gson.JsonParser;
import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.samplers.gui.AbstractSamplerGui;
import org.apache.jmeter.testelement.TestElement;

import javax.swing.*;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/**
 * @author liwen
 * Date  2025-09-03
 */
public class SseStreamSamplerGui extends AbstractSamplerGui {
    private JTextField tfUrl;
    private JTextArea taBody;
    private JTextArea taHeaders;
    private JTextField tfConnectTimeout;
    private JTextField tfReadTimeout;
    private JLabel lblUrlError;
    private JLabel lblBodyError;
    private JLabel lblTimeoutError;

    public SseStreamSamplerGui() {
        init();
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());
        add(makeTitlePanel(), BorderLayout.NORTH);

        VerticalPanel mainPanel = new VerticalPanel();

        JPanel urlPanel = new JPanel(new BorderLayout());
        tfUrl = new JTextField();
        lblUrlError = new JLabel(" ");
        lblUrlError.setForeground(Color.RED);
        lblUrlError.setFont(lblUrlError.getFont().deriveFont(Font.PLAIN, 11f));
        urlPanel.add(lblUrlError, BorderLayout.NORTH);
        urlPanel.add(createLabelPanel("SSE Url (*必填):", tfUrl), BorderLayout.CENTER);
        mainPanel.add(urlPanel);

        JPanel headerPanel = new JPanel(new BorderLayout());
        taHeaders = new JTextArea(4, 60);
        headerPanel.add(createLabelPanel("Headers (key:value 每行一个):", new JScrollPane(taHeaders)), BorderLayout.CENTER);
        mainPanel.add(headerPanel);

        JPanel bodyPanel = new JPanel(new BorderLayout());
        taBody = new JTextArea(8, 60);
        lblBodyError = new JLabel(" ");
        lblBodyError.setForeground(Color.RED);
        lblBodyError.setFont(lblBodyError.getFont().deriveFont(Font.PLAIN, 11f));
        bodyPanel.add(lblBodyError, BorderLayout.NORTH);
        bodyPanel.add(createLabelPanel("Request Body JSON (*必填):", new JScrollPane(taBody)), BorderLayout.CENTER);
        mainPanel.add(bodyPanel);

        JPanel timePanel = new JPanel(new GridLayout(1, 2));
        tfConnectTimeout = new JTextField("10000");
        tfReadTimeout = new JTextField("60000");
        lblTimeoutError = new JLabel(" ");
        lblTimeoutError.setForeground(Color.RED);
        lblTimeoutError.setFont(lblTimeoutError.getFont().deriveFont(Font.PLAIN, 11f));

        JPanel connectPanel = createLabelPanel("ConnectTimeout(ms) (*必填)", tfConnectTimeout);
        JPanel readPanel = createLabelPanel("ReadTimeout(ms) (*必填)", tfReadTimeout);
        timePanel.add(connectPanel);
        timePanel.add(readPanel);
        mainPanel.add(timePanel);
        mainPanel.add(lblTimeoutError);

        addFocusListeners();

        add(mainPanel, BorderLayout.CENTER);
    }

    private void addFocusListeners() {
        tfUrl.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                validateUrl();
            }
        });

        taBody.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                validateBody();
            }
        });

        tfConnectTimeout.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                validateTimeouts();
            }
        });

        tfReadTimeout.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                validateTimeouts();
            }
        });
    }

    private boolean validateUrl() {
        String url = tfUrl.getText().trim();
        if (url.isEmpty()) {
            lblUrlError.setText("URL不能为空");
            return false;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            lblUrlError.setText("URL必须以http://或https://开头");
            return false;
        }
        lblUrlError.setText(" ");
        return true;
    }

    private boolean validateBody() {
        String body = taBody.getText().trim();
        if (body.isEmpty()) {
            lblBodyError.setText("请求体不能为空");
            return false;
        }
        try {
            JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            lblBodyError.setText("JSON格式错误: " + e.getMessage());
            return false;
        }
        lblBodyError.setText(" ");
        return true;
    }

    private boolean validateTimeouts() {
        String ct = tfConnectTimeout.getText().trim();
        String rt = tfReadTimeout.getText().trim();
        if (ct.isEmpty() || rt.isEmpty()) {
            lblTimeoutError.setText("超时时间不能为空");
            return false;
        }
        try {
            int c = Integer.parseInt(ct);
            int r = Integer.parseInt(rt);
            if (c <= 0 || r <= 0) {
                lblTimeoutError.setText("超时时间必须为正整数");
                return false;
            }
        } catch (NumberFormatException e) {
            lblTimeoutError.setText("超时时间必须为数字");
            return false;
        }
        lblTimeoutError.setText(" ");
        return true;
    }

    public boolean validateAll() {
        boolean urlOk = validateUrl();
        boolean bodyOk = validateBody();
        boolean timeoutOk = validateTimeouts();
        return urlOk && bodyOk && timeoutOk;
    }

    private JPanel createLabelPanel(String label, JComponent comp) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JLabel(label), BorderLayout.NORTH);
        p.add(comp, BorderLayout.CENTER);
        return p;
    }

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
        sampler.setProperty(SseStreamSampler.HEADERS, taHeaders.getText());
        sampler.setProperty(SseStreamSampler.CONNECT_TIMEOUT, tfConnectTimeout.getText().trim());
        sampler.setProperty(SseStreamSampler.READ_TIMEOUT, tfReadTimeout.getText().trim());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);
        SseStreamSampler sampler = (SseStreamSampler) element;
        tfUrl.setText(sampler.getPropertyAsString(SseStreamSampler.URL));
        taBody.setText(sampler.getPropertyAsString(SseStreamSampler.REQUEST_BODY));
        taHeaders.setText(sampler.getPropertyAsString(SseStreamSampler.HEADERS));
        tfConnectTimeout.setText(sampler.getPropertyAsString(SseStreamSampler.CONNECT_TIMEOUT));
        tfReadTimeout.setText(sampler.getPropertyAsString(SseStreamSampler.READ_TIMEOUT));
    }

    @Override
    public String getLabelResource() {
        return "SSE-LLM Stream Sampler";
    }

    @Override
    public String getStaticLabel() {
        return "SSE-LLM Stream Sampler";
    }
}