import com.google.gson.Gson
import com.llm.perf.sse.SseEvent
import com.llm.perf.sse.SseMetrics
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

// ========== 共享资源（跨请求复用） ==========
if (vars.get("_sse_cm") == null) {
    def cm = new PoolingHttpClientConnectionManager()
    cm.setMaxTotal(200)
    cm.setDefaultMaxPerRoute(50)
    vars.putObject("_sse_cm", cm)
    vars.putObject("_sse_client", HttpClients.custom()
        .setConnectionManager(cm)
        .evictIdleConnections(60, java.util.concurrent.TimeUnit.SECONDS)
        .build())
    vars.putObject("_sse_gson", new Gson())
}

def client = vars.getObject("_sse_client")
def gson = vars.getObject("_sse_gson")

// ========== 参数 ==========
def url = vars.get("sse_url")
def body = vars.get("sse_body")
def headers = vars.get("sse_headers")
def connectTimeout = vars.get("connect_timeout")?.toInteger() ?: 10000
def readTimeout = vars.get("read_timeout")?.toInteger() ?: 60000

if (!url || url.trim().isEmpty()) {
    prev.setSuccessful(false)
    prev.setResponseMessage("URL不能为空")
    return
}
if (!body || body.trim().isEmpty()) {
    prev.setSuccessful(false)
    prev.setResponseMessage("请求体不能为空")
    return
}

// ========== 构建请求 ==========
def httpPost = new HttpPost(url.trim())
httpPost.setConfig(RequestConfig.custom()
    .setConnectTimeout(connectTimeout)
    .setSocketTimeout(readTimeout)
    .setConnectionRequestTimeout(connectTimeout)
    .build())

if (headers && !headers.trim().isEmpty()) {
    headers.split("\n").each { line ->
        def kv = line.split(":", 2)
        if (kv.length == 2) {
            httpPost.setHeader(kv[0].trim(), kv[1].trim())
        }
    }
}

httpPost.setEntity(new StringEntity(body, "UTF-8"))

// ========== 执行请求 & 解析SSE流 ==========
def metrics = new SseMetrics()
metrics.requestStartTime = System.currentTimeMillis()

def reader = null
def success = true
def responseMsg = ""

try {
    def response = client.execute(httpPost)
    def code = response.getStatusLine().getStatusCode()

    if (code >= 400) {
        success = false
        responseMsg = "HTTP error code: ${code}"
    } else {
        def entity = response.getEntity()
        if (entity != null) {
            reader = new BufferedReader(new InputStreamReader(entity.getContent(), "UTF-8"))
            def firstByte = true
            def firstTokenSet = false

            def line
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) continue

                def now = System.currentTimeMillis()
                if (firstByte) {
                    metrics.firstByteTime = now
                    firstByte = false
                }

                def evt = SseEvent.parse(line)
                if (evt.isDone()) break

                def json = evt.getJsonPayload()
                if (json == null) continue

                if (json.has("choices")) {
                    def choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        def choice = choices.get(0).getAsJsonObject()
                        if (choice.has("delta")) {
                            def delta = choice.getAsJsonObject("delta")
                            if (delta.has("content")) {
                                def content = delta.get("content")
                                if (content != null && !content.isJsonNull()) {
                                    def text = content.getAsString()
                                    if (text != null && !text.isEmpty()) {
                                        metrics.tokenCount++
                                        metrics.lastTokenTime = now
                                        if (!firstTokenSet) {
                                            metrics.firstTokenTime = now
                                            firstTokenSet = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (json.has("usage")) {
                    def usage = json.getAsJsonObject("usage")
                    if (usage != null) {
                        if (usage.has("prompt_tokens")) {
                            metrics.inputTokens = usage.get("prompt_tokens").getAsLong()
                        }
                        if (usage.has("completion_tokens")) {
                            metrics.outputTokens = usage.get("completion_tokens").getAsLong()
                        }
                    }
                }
            }
        }
    }
} catch (Exception e) {
    success = false
    responseMsg = e.getMessage()
} finally {
    if (reader != null) {
        try { reader.close() } catch (ignored) {}
    }
}

metrics.requestEndTime = System.currentTimeMillis()

// ========== 设置结果 ==========
prev.setSuccessful(success)
prev.setResponseMessage(responseMsg)

prev.setResponseData(
    "TTFT(ms):${metrics.getTTFT()}\n" +
    "TTFB(ms):${metrics.getTTFB()}\n" +
    "TPOT(ms/token):${String.format('%.2f', metrics.getTPOT())}\n" +
    "Token/s:${String.format('%.2f', metrics.getTokenPerSec())}\n" +
    "TotalRT(ms):${metrics.getTotalRT()}\n" +
    "inputTokens:${metrics.inputTokens}\n" +
    "outputTokens:${metrics.outputTokens}\n" +
    "tokenCount:${metrics.tokenCount}",
    "UTF-8")

prev.setSamplerData(gson.toJson(metrics))

vars.put("TTFT", String.valueOf(metrics.getTTFT()))
vars.put("TTFB", String.valueOf(metrics.getTTFB()))
vars.put("TPOT", String.format("%.2f", metrics.getTPOT()))
vars.put("TokenPerSec", String.format("%.2f", metrics.getTokenPerSec()))
vars.put("TotalRT", String.valueOf(metrics.getTotalRT()))
vars.put("inputTokens", String.valueOf(metrics.inputTokens))
vars.put("outputTokens", String.valueOf(metrics.outputTokens))
vars.put("tokenCount", String.valueOf(metrics.tokenCount))


/**
 * 使用方式：
 * 1. JMeter中添加 JSR223 Sampler
 * 2. Language 选 Groovy
 * 3. 脚本内容粘贴此文件内容，或用 Script file 指向此文件
 * 4. 配置变量：
 * sse_url = https://api.example.com/v1/chat/completions
 * sse_body = {"model":"gpt-4","messages":[{"role":"user","content":"hello"}],"stream":true}
 * sse_headers = Content-Type: application/json
 * Authorization: Bearer sk-xxx
 *
 *
 * 1. View Results Tree（查看结果树）
 直接看每个请求的原始数据：
 Sampler Result 标签页:
 Response Data 内容:
 TTFT(ms):120
 TTFB(ms):45
 TPOT(ms/token):22.30
 Token/s:44.80
 TotalRT(ms):1100
 inputTokens:50
 outputTokens:200
 tokenCount:200

 Sampler Data 内容:
 {"requestStartTime":...,"firstByteTime":...,"tokenCount":200,...}
 2. Summary Report / Aggregate Report
 只能看到标准指标（RT、TPS、错误率），看不到 TTFT/TPOT/Token等自定义指标。
 3. 用JSR223 Listener自己提取
 添加 JSR223 Listener，Language选Groovy，写：
 def data = prev.getResponseDataAsString()
 log.info("=== SSE Metrics ===\n${data}")
 结果在 jmeter.log 中查看。
 */
