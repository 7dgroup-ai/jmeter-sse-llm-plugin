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
  "stream": true,
  "stream_options": {"include_usage": true}
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

### 单次采样指标（SseMetrics）

| 指标 | 说明 | 计算公式 |
|------|------|----------|
| TTFT | 首个 token 延迟 | `firstTokenTime - requestStartTime` |
| TTFB | 首字节延迟 | `firstByteTime - requestStartTime` |
| TPOT | 每个输出 chunk 耗时（近似） | `(lastTokenTime - firstTokenTime) / (tokenCount - 1)` |
| Token/s | 吞吐率（按 chunk 累加数，近似） | `tokenCount / duration * 1000` |
| RealToken/s | 真实吞吐率（基于 usage，推荐） | `outputTokens / (requestEndTime - requestStartTime) * 1000`，无 usage 返回 -1 |
| TTFT-TTFB | 首字节到首 token 的生成准备时间 | `firstTokenTime - firstByteTime` |
| MaxGap | 最大相邻 token 间隔（断流检测） | `max(lastTokenTime 间隔)` |
| Stall | 卡顿次数 | 相邻 token 间隔超过 1s 的次数 |
| TotalRT | 总响应时间 | `requestEndTime - requestStartTime` |

> **说明**：`tokenCount` 的语义是"逐 chunk 累加数"（收到一次 content 加 1），因此派生出的
> TPOT / Token·s 为基于 chunk 的近似指标，用于监控流式节奏；需要精确的 token 吞吐时请以
> **RealToken/s**（基于服务端 usage）为准。由于 Gemini usage 的 `totalTokenCount` 会破坏该
> 计数的一致性，采样器不再用它覆盖 `tokenCount`。

### 监听器聚合统计（Aggregate Statistics）

- 单次运行实时聚合：平均值、TTFT min/max、**分位数 P50/P90/P95/P99**（TTFT/TTFB/TotalRT）、标准差、**错误率 %**、真实 Token/s、最大 token 间隔、卡顿总数、In/Out token 总量
- 右键 **Copy Aggregate Statistics (TSV)** 导出为一行（含表头，可直接粘贴 Excel）；**Copy & Clear** 复制后清空，多次运行逐行拼接生成报告
- 表格新增 RealTok/s、TTFT-TTFB、MaxGap、Stall 列

### 后端推送指标

InfluxDB 数据点字段除上述单次指标外，额外包含 `real_token_per_sec`、`ttft_minus_ttfb`、`max_token_gap`、`stall_count`。

## 日志控制

采样器使用 SLF4J 日志，可在 `JMETER_HOME/bin/jmeter.properties` 中通过 `log_level.` 前缀控制输出级别（无需改代码，重启/重新加载后生效）。

| 级别 | 输出内容 |
|------|----------|
| `INFO`（默认） | 每次采样的摘要：URL、成功状态、`in/outTokens`、TTFT/TTFB/TPOT/Token/s/TotalRT |
| `DEBUG` | 逐渐输出每条 SSE chunk 原始行 + 完整流式拼接内容，便于核对流式输出是否正确 |
| `ERROR`/`WARN` | 请求失败、HTTP 错误等异常信息 |

启用方式（在 `jmeter.properties` 中添加）：

```properties
# 打开 DEBUG 看到所有采样日志（含逐 chunk 原始行与完整流式内容）
log_level.com.llm.perf.sse=DEBUG

# 仅看每次采样的指标摘要（默认即为 INFO，按需显式配置）
log_level.com.llm.perf.sse=INFO
```

> 提示：`DEBUG` 日志量大，仅调试时开启；压测时建议保持 `INFO` 或更高级别。日志写入 `jmeter.log`，JMeter GUI 中可通过「Options -> Log Viewer」查看。

### 关于 In/Out Token 统计的说明

- **OpenAI**：流式响应默认不返回 usage，需在请求体中添加 `"stream_options":{"include_usage":true}` 才会输出 `prompt_tokens` / `completion_tokens`（见下方 OpenAI 示例）。
- **Dify**：usage 在 `message_end` 事件的 `metadata.usage` 中解析。
- **Claude**：`input_tokens` 在 `message_start` 事件、`output_tokens` 在 `message_delta` 事件中解析。
- **Gemini**：usage 在 `step.completed` / `message.completed` / `interaction.completed` 事件的 `usageMetadata` 中解析。

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
