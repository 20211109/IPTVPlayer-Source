package com.iptv.player.config

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.net.URLDecoder

/**
 * 局域网配对服务。TV 端启动后，手机扫码访问 http://<TV_IP>:<port>，
 * 在网页中输入 M3U 订阅地址或粘贴播放列表文本，提交后回调 [onSubmit]。
 *
 * TV 无摄像头，采用"反向扫码"：TV 显示二维码，手机扫码完成输入。
 */
class PairingServer(
    port: Int = 8899,
    private val onSubmit: (text: String, isUrl: Boolean) -> Unit,
    /** 网页标题 / 说明 / 地址输入框占位符：直播源与节目单接口共用同一套页面 */
    private val pageTitle: String = "IPTV 直播源配置",
    private val pageHint: String = "输入 M3U 订阅地址，或直接粘贴播放列表文本；提交后电视端会自动加载。",
    private val urlPlaceholder: String = "https://example.com/list.m3u8"
) : NanoHTTPD(port) {

    @Volatile
    var isRunning = false
        private set

    fun startSafely() {
        try {
            start(3000, false) // 3s 超时, 非后台线程
            isRunning = true
        } catch (e: Exception) {
            isRunning = false
        }
    }

    fun stopSafely() {
        stop()
        isRunning = false
    }

    override fun serve(session: IHTTPSession): Response {
        return when {
            session.uri == "/" && session.method == Method.GET -> {
                newFixedLengthResponse(Status.OK, MIME_HTML, renderPage())
            }
            session.uri == "/submit" && session.method == Method.POST -> {
                // 优先取查询串参数：不同版本 NanoHTTPD 对表单体的处理不一致，
                // 页面会把短内容同时放进 URL，避免 body 解析失败导致「内容为空」。
                val queryMode = session.parameters["mode"]?.firstOrNull()
                val queryContent = session.parameters["content"]?.firstOrNull()
                val files = HashMap<String, String>()
                val form = try {
                    session.parseBody(files)
                    parseFormBody(files["postData"] ?: "")
                } catch (e: Exception) {
                    emptyMap<String, String>()
                }
                val mode = queryMode ?: form["mode"] ?: "url"
                val content = (queryContent ?: form["content"] ?: "").trim()
                if (content.isBlank()) return jsonResponse(false, "内容为空")
                onSubmit(content, mode == "url")
                jsonResponse(true, "已收到，正在加载…")
            }
            else -> newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }
    }

    /** 解析 application/x-www-form-urlencoded 请求体：a=1&b=2 */
    private fun parseFormBody(body: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (pair in body.split("&")) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawValue = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = try {
                URLDecoder.decode(rawKey, "UTF-8")
            } catch (e: Exception) {
                rawKey
            }
            val value = try {
                URLDecoder.decode(rawValue, "UTF-8")
            } catch (e: Exception) {
                rawValue
            }
            map[key] = value
        }
        return map
    }

    /** 按场景替换页面里的标题、说明与输入占位符 */
    private fun renderPage(): String = PAGE_HTML
        .replace("__TITLE__", pageTitle)
        .replace("__HINT__", pageHint)
        .replace("__PLACEHOLDER__", urlPlaceholder)

    private fun jsonResponse(ok: Boolean, msg: String): Response {
        val json = "{\"ok\":$ok,\"msg\":\"$msg\"}"
        val r = newFixedLengthResponse(Status.OK, MIME_JSON, json)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    companion object {
        private const val MIME_HTML = "text/html; charset=UTF-8"
        private const val MIME_JSON = "application/json; charset=UTF-8"

        private val PAGE_HTML = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__TITLE__</title>
<style>
  body{font-family:-apple-system,sans-serif;background:#12141a;color:#e8eaed;margin:0;padding:24px;}
  h1{font-size:20px;color:#4fc3f7;}
  p{color:#9aa0a6;font-size:14px;line-height:1.6;}
  input,textarea{width:100%;box-sizing:border-box;background:#1e212b;color:#fff;border:1px solid #3a3f4d;
    border-radius:8px;padding:12px;font-size:15px;margin:8px 0;}
  textarea{height:160px;font-family:monospace;font-size:12px;}
  button{width:100%;background:#4fc3f7;color:#111;border:none;border-radius:8px;
    padding:14px;font-size:16px;font-weight:bold;margin-top:8px;}
  .tab{display:flex;gap:8px;margin-top:16px;}
  .tab button{background:#2a2e3a;color:#ccc;margin:0;}
  .tab button.active{background:#4fc3f7;color:#111;}
  #msg{margin-top:16px;font-size:14px;color:#81c995;}
  .hide{display:none;}
</style>
</head>
<body>
  <h1>__TITLE__</h1>
  <p>__HINT__</p>
  <div class="tab">
    <button id="tabUrl" class="active" onclick="switchTab('url')">订阅地址</button>
    <button id="tabText" onclick="switchTab('text')">粘贴文本</button>
  </div>
  <form onsubmit="submitForm(event)">
    <div id="boxUrl">
      <input id="contentUrl" type="url" placeholder="__PLACEHOLDER__" autocomplete="off">
    </div>
    <div id="boxText" class="hide">
      <textarea id="contentText" placeholder="#EXTM3U&#10;#EXTINF:-1,CCTV-1&#10;http://..."></textarea>
    </div>
    <button type="submit">发送到电视</button>
  </form>
  <div id="msg"></div>
<script>
var mode='url';
function switchTab(m){
  mode=m;
  document.getElementById('tabUrl').className = m=='url'?'active':'';
  document.getElementById('tabText').className = m=='text'?'active':'';
  document.getElementById('boxUrl').className = m=='url'?'':'hide';
  document.getElementById('boxText').className = m=='text'?'':'hide';
}
function submitForm(e){
  e.preventDefault();
  var c = mode=='url' ? document.getElementById('contentUrl').value.trim()
                      : document.getElementById('contentText').value.trim();
  if(!c){document.getElementById('msg').innerText='请输入内容';return;}
  var body = 'mode='+encodeURIComponent(mode)+'&content='+encodeURIComponent(c);
  // 短内容同时放进查询串：部分设备上表单体解析会失败，查询串方式最稳
  var url = '/submit';
  if(c.length<=1500){url += '?mode='+encodeURIComponent(mode)+'&content='+encodeURIComponent(c);body='';}
  fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
    .then(function(r){return r.json();})
    .then(function(j){document.getElementById('msg').innerText=j.msg;})
    .catch(function(err){document.getElementById('msg').innerText='发送失败: '+err;});
}
</script>
</body>
</html>
""".trimIndent()
    }
}
