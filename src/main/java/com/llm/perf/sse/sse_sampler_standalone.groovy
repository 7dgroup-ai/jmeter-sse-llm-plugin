import groovy.json.JsonSlurper
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClients
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

// ========== 共享资源（跨请求复用） ==========
if (vars.get("_standalone_cm") == null) {
    def cm = new PoolingHttpClientConnectionManager()
    cm.setMaxTotal(200)
    cm.setDefaultMaxPerRoute(50)
    vars.putObject("_standalone_cm", cm)
    vars.putObject("_standalone_client", HttpClients.custom()
        .setConnectionManager(cm)
        .evictIdleConnections(60, java.util.concurrent.TimeUnit.SECONDS)
        .build())
    vars.putObject("_standalone_slurper", new JsonSlurper())
}

def client = vars.getObject("_standalone_client")
def jsonSlurper = vars.getObject("_standalone_slurper")

// ========== 参数 ==========
def url = vars.get("sse_url") ?: ""
def body = vars.get("sse_body") ?: ""
def headers = vars.get("sse_headers") ?: ""
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

// ========== 指标变量 ==========
long requestStartTime = System.currentTimeMillis()
long firstByteTime = -1
long firstTokenTime = -1
long lastTokenTime = -1
long inputTokens = 0
long outputTokens = 0
long tokenCount = 0
boolean firstTokenSet = false

// ========== 执行请求 & 解析SSE流 ==========
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

            def line
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) continue

                def now = System.currentTimeMillis()
                if (firstByte) {
                    firstByteTime = now
                    firstByte = false
                }

                def data = line.substring(5).trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue

                def json
                try {
                    json = jsonSlurper.parseText(data)
                } catch (Exception e) {
                    continue
                }

                if (json.choices) {
                    def choice = json.choices[0]
                    if (choice?.delta?.content) {
                        tokenCount++
                        lastTokenTime = now
                        if (!firstTokenSet) {
                            firstTokenTime = now
                            firstTokenSet = true
                        }
                    }
                }

                if (json.usage) {
                    inputTokens = json.usage.prompt_tokens ?: 0
                    outputTokens = json.usage.completion_tokens ?: 0
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

long requestEndTime = System.currentTimeMillis()

// ========== 计算指标 ==========
long ttft = (firstTokenTime > 0 && requestStartTime > 0) ? Math.max(0, firstTokenTime - requestStartTime) : -1
long ttfb = (firstByteTime > 0 && requestStartTime > 0) ? Math.max(0, firstByteTime - requestStartTime) : -1
long totalRT = (requestEndTime > 0 && requestStartTime > 0) ? Math.max(0, requestEndTime - requestStartTime) : -1
double tpot = (firstTokenTime > 0 && lastTokenTime > 0 && tokenCount > 1) ?
    (double)(lastTokenTime - firstTokenTime) / (tokenCount - 1) : -1d
double tokenPerSec = (firstTokenTime > 0 && lastTokenTime > 0 && tokenCount > 0) ?
    (double) tokenCount / (lastTokenTime - firstTokenTime) * 1000 : -1d

// ========== 设置结果 ==========
prev.setSuccessful(success)
prev.setResponseMessage(responseMsg)

def sb = new StringBuilder(256)
sb.append("TTFT(ms):").append(ttft).append('\n')
sb.append("TTFB(ms):").append(ttfb).append('\n')
sb.append("TPOT(ms/token):").append(tpot >= 0 ? String.format('%.2f', tpot) : '-').append('\n')
sb.append("Token/s:").append(tokenPerSec >= 0 ? String.format('%.2f', tokenPerSec) : '-').append('\n')
sb.append("TotalRT(ms):").append(totalRT).append('\n')
sb.append("inputTokens:").append(inputTokens).append('\n')
sb.append("outputTokens:").append(outputTokens).append('\n')
sb.append("tokenCount:").append(tokenCount)
prev.setResponseData(sb.toString(), "UTF-8")

vars.put("TTFT", String.valueOf(ttft))
vars.put("TTFB", String.valueOf(ttfb))
vars.put("TPOT", tpot >= 0 ? String.format("%.2f", tpot) : "-1")
vars.put("TokenPerSec", tokenPerSec >= 0 ? String.format("%.2f", tokenPerSec) : "-1")
vars.put("TotalRT", String.valueOf(totalRT))
vars.put("inputTokens", String.valueOf(inputTokens))
vars.put("outputTokens", String.valueOf(outputTokens))
vars.put("tokenCount", String.valueOf(tokenCount))


/**
 * 使用方式（零依赖，不需要部署插件jar）：
 * 1. JMeter中添加 JSR223 Sampler
 * 2. Language 选 Groovy
 * 3. 脚本内容粘贴此文件，或用 Script file 指向此文件
 * 4. 在 User Defined Variables 中配置：
 *    sse_url = https://api.example.com/v1/chat/completions
 *    sse_body = {"model":"gpt-4","messages":[{"role":"user","content":"hello"}],"stream":true}
 *    sse_headers = Content-Type: application/json
 *                  Authorization: Bearer sk-xxx
 * 5. 输出变量：${TTFT} ${TTFB} ${TPOT} ${TokenPerSec} ${TotalRT} ${inputTokens} ${outputTokens} ${tokenCount}
 */
