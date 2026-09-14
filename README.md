# JMeter SSE LLM Plugin

一个用于测试流式 LLM API（如 OpenAI Chat Completions Stream）性能的 JMeter 插件。

> Developed by [7DGroup](https://github.com/7dgroup-ai)

## 功能特性

- 支持 SSE (Server-Sent Events) 协议解析
- 自动计算关键性能指标：
  - **TTFT** (Time To First Token) - 首个 token 延迟
  - **TTFB** (Time To First Byte) - 首字节延迟
  - **TPOT** (Time Per Output Token) - 每个输出 token 耗时
  - **Token/s** - 吞吐率
  - **TotalRT** (Total Response Time) - 总响应时间
- 实时 GUI 监控面板，展示聚合统计
- 支持推送到 InfluxDB 等后端系统
- **支持多种 LLM API 格式**：OpenAI、Dify、Anthropic Claude、Google Gemini

## 环境要求

- JMeter 5.6.3+
- Java 8+

## 安装

1. 编译打包：
   ```bash
   mvn clean package
   ```

2. 将生成的 jar 包复制到 JMeter 的 `lib/ext` 目录：
   ```bash
   cp target/jmeter-sse-llm-plugin-1.0.0.jar $JMETER_HOME/lib/ext/
   ```

3. 重启 JMeter

## 使用方法

### 1. 添加 SSE Stream Sampler

1. 在测试计划中右键 -> 添加 -> Sampler -> **SSE Stream Sampler**
2. 配置参数：
   - **URL**: LLM API 地址（如 `https://api.openai.com/v1/chat/completions`）
   - **API Type**: 选择 API 类型（`openai`、`dify`、`claude`、`gemini`），默认为 `openai`
   - **Request Body**: JSON 格式的请求体
   - **Headers**: 请求头（每行一个，格式：`HeaderName:HeaderValue`）
   - **Connect Timeout**: 连接超时时间（毫秒）
   - **Read Timeout**: 读取超时时间（毫秒）

### 2. 添加 SSE Metrics Listener

1. 在测试计划中右键 -> 添加 -> Listener -> **SSE-LLM Metrics Listener**
2. 运行测试，实时查看聚合统计

### 3. 配置示例

#### OpenAI 格式
```
URL: https://api.openai.com/v1/chat/completions
API Type: openai
Headers:
Authorization:Bearer sk-xxx
Content-Type:application/json
Request Body:
{
  "model": "gpt-3.5-turbo",
  "messages": [{"role": "user", "content": "Hello"}],
  "stream": true
}
```

#### Dify 格式
```
URL: https://your-dify-instance.com/api/v1/chat-messages
API Type: dify
Headers:
Authorization:Bearer sk-xxx
Content-Type:application/json
Request Body:
{
  "query": "Hello",
  "response_mode": "streaming",
  "conversation_id": ""
}
```

#### Anthropic Claude 格式
```
URL: https://api.anthropic.com/v1/messages
API Type: claude
Headers:
x-api-key: sk-xxx
anthropic-version: 2023-06-01
Content-Type:application/json
Request Body:
{
  "model": "claude-3-sonnet-20240229",
  "max_tokens": 1024,
  "stream": true,
  "messages": [{"role": "user", "content": "Hello"}]
}
```

#### Google Gemini 格式
```
URL: https://generativelanguage.googleapis.com/v1beta/interactions
API Type: gemini
Headers:
x-goog-api-key: $GEMINI_API_KEY
Content-Type:application/json
Request Body:
{
  "model": "gemini-1.5-flash",
  "input": "Hello",
  "stream": true
}
```

## 指标说明

| 指标 | 说明 | 计算公式 |
|------|------|----------|
| TTFT | 首个 token 延迟 | `firstTokenTime - requestStartTime` |
| TTFB | 首字节延迟 | `firstByteTime - requestStartTime` |
| TPOT | 每个输出 token 耗时 | `(lastTokenTime - firstTokenTime) / (tokenCount - 1)` |
| Token/s | 吞吐率 | `tokenCount / duration * 1000` |
| TotalRT | 总响应时间 | `requestEndTime - requestStartTime` |

## 后端推送配置

如需推送到 InfluxDB，可添加 **SSE-LLM Backend Listener**，配置参数：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| endpointUrl | `http://localhost:8086/write?db=jmeter` | InfluxDB 写入地址 |
| authToken | - | 认证令牌（可选） |
| flushIntervalMs | 5000 | 推送间隔（毫秒） |
| batchSize | 50 | 批次大小 |
| application | jmeter-sse | 应用标签 |
| measurement | sse_llm_metrics | 测量名称 |

## 项目结构

```
src/main/java/com/llm/perf/sse/
├── SseStreamSampler.java         # 核心采样器
├── SseStreamSamplerGui.java      # 采样器 GUI
├── SseMetrics.java               # 指标数据模型
├── SseEvent.java                 # SSE 协议解析
├── SseMetricsListenerGui.java    # 监听器 GUI
└── SseBackendListenerClient.java # 后端推送客户端
```

## License

[GNU General Public License v3.0](LICENSE)
