package com.mingjin.school_wechat.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/browser")
public class BrowserProxyController {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ConcurrentHashMap<String, String> cookieStore = new ConcurrentHashMap<>();

    private static final List<String> HOP_BY_HOP = List.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade"
    );

    private String getCookieKey(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() != -1 ? ":" + uri.getPort() : "");
    }

    private void storeCookies(URI uri, List<String> setCookieHeaders) {
        String key = getCookieKey(uri);
        String existing = cookieStore.getOrDefault(key, "");
        java.util.Map<String, String> cookieMap = new java.util.LinkedHashMap<>();
        for (String part : existing.split(";\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                cookieMap.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        for (String sc : setCookieHeaders) {
            String cookiePart = sc.split(";\\s*")[0];
            int eq = cookiePart.indexOf('=');
            if (eq > 0) {
                String name = cookiePart.substring(0, eq).trim();
                String value = cookiePart.substring(eq + 1).trim();
                if (value.isEmpty()) {
                    cookieMap.remove(name);
                } else {
                    cookieMap.put(name, value);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        cookieMap.forEach((k, v) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(k).append("=").append(v);
        });
        if (sb.length() > 0) {
            cookieStore.put(key, sb.toString());
        } else {
            cookieStore.remove(key);
        }
    }

    private String getCookiesForUri(URI uri) {
        String key = getCookieKey(uri);
        return cookieStore.getOrDefault(key, "");
    }

    @RequestMapping(value = "/search", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> search(@RequestParam String engine, @RequestParam String q) {
        try {
            String keyword = q == null ? "" : q.trim();
            if (keyword.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", true, "results", List.of()));
            }

            String targetUrl;
            if ("bilibili".equalsIgnoreCase(engine)) {
                targetUrl = "https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword=" + encodeURIComponent(keyword);
            } else {
                targetUrl = "https://www.baidu.com/s?wd=" + encodeURIComponent(keyword);
                engine = "baidu";
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(targetUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "identity")
                    .header("Accept", "text/html,application/json,*/*")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String text = new String(response.body(), resolveCharset(response.headers().firstValue("Content-Type").orElse(""), response.body()));
            List<Map<String, String>> results = "bilibili".equalsIgnoreCase(engine)
                    ? parseBilibiliResults(text)
                    : parseBaiduResults(text);
            return ResponseEntity.ok(Map.of("success", true, "engine", engine, "query", keyword, "results", results));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", ex.getMessage() == null ? "搜索失败" : ex.getMessage(),
                    "results", List.of()
            ));
        }
    }

    @RequestMapping(value = "/bilibili-player", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> bilibiliPlayer(@RequestParam String url) {
        try {
            String bvid = "";
            String aid = "";
            java.util.regex.Matcher idMatcher = java.util.regex.Pattern
                    .compile("/video/(BV[a-zA-Z0-9]+|av\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url == null ? "" : url);
            if (idMatcher.find()) {
                String id = idMatcher.group(1);
                if (id.toLowerCase().startsWith("av")) {
                    aid = id.substring(2);
                } else {
                    bvid = id;
                }
            }
            if (bvid.isEmpty() && aid.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法识别 B 站视频 ID"));
            }

            String apiUrl = "https://api.bilibili.com/x/web-interface/view?"
                    + (!bvid.isEmpty() ? "bvid=" + encodeURIComponent(bvid) : "aid=" + encodeURIComponent(aid));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String json = new String(response.body(), StandardCharsets.UTF_8);
            String cid = extractJsonNumber(json, "cid");
            String resolvedAid = aid.isEmpty() ? extractJsonNumber(json, "aid") : aid;
            String resolvedBvid = bvid.isEmpty() ? extractJsonString(json, "bvid") : bvid;
            if (cid.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法获取视频分 P 信息"));
            }
            StringBuilder playerUrl = new StringBuilder("https://player.bilibili.com/player.html?isOutside=true");
            if (!resolvedBvid.isEmpty()) {
                playerUrl.append("&bvid=").append(encodeURIComponent(resolvedBvid));
            }
            if (!resolvedAid.isEmpty()) {
                playerUrl.append("&aid=").append(encodeURIComponent(resolvedAid));
            }
            playerUrl.append("&cid=").append(encodeURIComponent(cid))
                    .append("&p=1&autoplay=0&high_quality=1");
            return ResponseEntity.ok(Map.of("success", true, "playerUrl", playerUrl.toString()));
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", ex.getMessage() == null ? "视频加载失败" : ex.getMessage()
            ));
        }
    }

    @RequestMapping(value = "/empty", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.OPTIONS})
    public ResponseEntity<String> empty() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "*");
        return new ResponseEntity<>("{}", headers, HttpStatus.OK);
    }

    @RequestMapping(value = "/proxy", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.OPTIONS})
    public ResponseEntity<byte[]> proxy(@RequestParam String url, HttpServletRequest servletRequest) {
        if ("OPTIONS".equals(servletRequest.getMethod())) {
            HttpHeaders corsHeaders = new HttpHeaders();
            corsHeaders.set("Access-Control-Allow-Origin", "*");
            corsHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            corsHeaders.set("Access-Control-Allow-Headers", "*");
            corsHeaders.set("Access-Control-Max-Age", "86400");
            corsHeaders.set("Access-Control-Allow-Credentials", "true");
            return new ResponseEntity<>(corsHeaders, HttpStatus.OK);
        }

        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return ResponseEntity.badRequest().build();
            }

            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            String method = servletRequest.getMethod();

            boolean isDouyin = isDouyinUrl(url);
            String userAgent = isDouyin
                    ? "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                    : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "identity")
                    .header("Referer", extractReferer(url));

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                reqBuilder.header("Origin", uri.getScheme() + "://" + uri.getAuthority());
            }

            String accept = servletRequest.getHeader("Accept");
            if (accept != null && !accept.isEmpty()) {
                reqBuilder.header("Accept", accept);
            } else {
                reqBuilder.header("Accept", "*/*");
            }

            String serverCookies = getCookiesForUri(uri);
            if (!serverCookies.isEmpty()) {
                reqBuilder.header("Cookie", serverCookies);
            }

            String authorization = servletRequest.getHeader("Authorization");
            if (authorization != null && !authorization.isEmpty()) {
                reqBuilder.header("Authorization", authorization);
            }

            String range = servletRequest.getHeader("Range");
            if (range != null && !range.isEmpty()) {
                reqBuilder.header("Range", range);
            }

            String ifRange = servletRequest.getHeader("If-Range");
            if (ifRange != null && !ifRange.isEmpty()) {
                reqBuilder.header("If-Range", ifRange);
            }

            String ifNoneMatch = servletRequest.getHeader("If-None-Match");
            if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
                reqBuilder.header("If-None-Match", ifNoneMatch);
            }

            String ifModifiedSince = servletRequest.getHeader("If-Modified-Since");
            if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
                reqBuilder.header("If-Modified-Since", ifModifiedSince);
            }

            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                byte[] requestBody = servletRequest.getInputStream().readAllBytes();
                String contentType = servletRequest.getContentType();
                if (contentType != null && !contentType.isEmpty()) {
                    reqBuilder.header("Content-Type", contentType);
                }
                if (requestBody.length > 0) {
                    reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
                } else {
                    reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(new byte[0]));
                }
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpRequest httpRequest = reqBuilder.build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());

            int statusCode = response.statusCode();

            List<String> setCookies = response.headers().allValues("Set-Cookie");
            if (!setCookies.isEmpty()) {
                storeCookies(uri, setCookies);
            }

            if (statusCode == 304) {
                HttpHeaders h304 = new HttpHeaders();
                h304.set("Access-Control-Allow-Origin", "*");
                h304.set("Access-Control-Allow-Credentials", "true");
                response.headers().allValues("ETag").forEach(v -> h304.add("ETag", v));
                response.headers().allValues("Last-Modified").forEach(v -> h304.add("Last-Modified", v));
                return new ResponseEntity<>(new byte[0], h304, HttpStatus.NOT_MODIFIED);
            }

            if (statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308) {
                String location = response.headers().firstValue("Location").orElse("");
                if (!location.isEmpty()) {
                    String resolvedLocation;
                    try {
                        resolvedLocation = new URL(new URL(url), location).toString();
                    } catch (Exception e) {
                        resolvedLocation = location;
                    }
                    String proxyLocation = "/api/browser/proxy?url=" + encodeURIComponent(resolvedLocation);
                    HttpHeaders redirectHeaders = new HttpHeaders();
                    redirectHeaders.set("Location", proxyLocation);
                    redirectHeaders.set("Access-Control-Allow-Origin", "*");
                    redirectHeaders.set("Access-Control-Allow-Credentials", "true");
                    return new ResponseEntity<>(new byte[0], redirectHeaders, HttpStatus.valueOf(statusCode));
                }
            }

            byte[] body = response.body();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            if ("gzip".equalsIgnoreCase(contentEncoding)) {
                try (var bais = new java.io.ByteArrayInputStream(body);
                     var gzis = new java.util.zip.GZIPInputStream(bais);
                     var baos = new java.io.ByteArrayOutputStream()) {
                    gzis.transferTo(baos);
                    body = baos.toByteArray();
                } catch (Exception e) { /* ignore decompression error */ }
            } else if ("deflate".equalsIgnoreCase(contentEncoding)) {
                try (var bais = new java.io.ByteArrayInputStream(body);
                     var dis = new java.util.zip.InflaterInputStream(bais);
                     var baos = new java.io.ByteArrayOutputStream()) {
                    dis.transferTo(baos);
                    body = baos.toByteArray();
                } catch (Exception e) { /* ignore decompression error */ }
            } else if ("br".equalsIgnoreCase(contentEncoding)) {
                // Brotli not supported, skip
            }
            String respContentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");

            if (respContentType.contains("text/html")) {
                String html = new String(body, StandardCharsets.UTF_8);
                if (isBilibiliSearchUrl(url)) {
                    html = stripPageScripts(html);
                }
                if (isDouyin) {
                    html = rewriteDouyinResourceAttrs(html, url);
                }
                html = modifyHtml(html, url);
                body = html.getBytes(StandardCharsets.UTF_8);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", respContentType);
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "*");
            headers.set("Access-Control-Allow-Credentials", "true");

            boolean isHtml = respContentType.contains("text/html");
            if (!isHtml) {
                response.headers().allValues("Content-Range").forEach(v -> headers.add("Content-Range", v));
                response.headers().allValues("Accept-Ranges").forEach(v -> headers.add("Accept-Ranges", v));
                if (contentEncoding.isEmpty()) {
                    response.headers().allValues("Content-Length").forEach(v -> headers.add("Content-Length", v));
                }
            }
            response.headers().allValues("ETag").forEach(v -> headers.add("ETag", v));
            response.headers().allValues("Last-Modified").forEach(v -> headers.add("Last-Modified", v));
            response.headers().allValues("Cache-Control").forEach(v -> headers.add("Cache-Control", v));

            return new ResponseEntity<>(body, headers, HttpStatus.valueOf(statusCode));
        } catch (Exception e) {
            String errorHtml = "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                    + "<style>body{display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:system-ui;color:#666;flex-direction:column;}"
                    + "h3{color:#333;margin-bottom:8px}p{margin:4px 0}</style></head>"
                    + "<body><h3>网页加载失败</h3><p>" + escapeHtml(e.getMessage()) + "</p>"
                    + "<p style='margin-top:16px;font-size:13px;color:#999'>请尝试刷新或返回主页</p></body></html>";
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(errorHtml.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String encodeURIComponent(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return s;
        }
    }

    private String extractReferer(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority() + u.getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private boolean isBilibiliSearchUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return "search.bilibili.com".equalsIgnoreCase(host)
                    || (host != null && host.endsWith(".bilibili.com") && path != null && path.startsWith("/all"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDouyinUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("douyin.com")
                    || host.endsWith(".douyin.com")
                    || host.equalsIgnoreCase("iesdouyin.com")
                    || host.endsWith(".iesdouyin.com")
                    || host.endsWith(".amemv.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private String stripPageScripts(String html) {
        return html.replaceAll("(?is)<script\\b(?![^>]*type=[\"']application/ld\\+json[\"'])[^>]*>.*?</script>", "");
    }

    private String modifyHtml(String html, String originalUrl) {
        String baseTag = "<base href=\"" + escapeAttr(originalUrl) + "\" target=\"_self\">";

        String script = "<script>(function(){"
                + "var _realTop=window.top;"
                + "try{Object.defineProperty(window,'parent',{get:function(){return window;}});}catch(e){}"
                + "try{Object.defineProperty(window,'top',{get:function(){return window;}});}catch(e){}"
                + "var _bo;"
                + "try{_bo=new URL(document.baseURI).origin;}catch(e){}"
                + "var _lo=window.location.origin;"
                + "var _isDouyin=false;"
                + "try{var _h=new URL(document.baseURI).hostname;_isDouyin=_h==='douyin.com'||/\\.douyin\\.com$/.test(_h)||_h==='iesdouyin.com'||/\\.iesdouyin\\.com$/.test(_h)||/\\.amemv\\.com$/.test(_h);}catch(e){}"
                + "function navigateTo(u){"
                + "if(!u||u.indexOf('javascript:')===0||u.indexOf('#')===0)return false;"
                + "var resolved;"
                + "try{resolved=new URL(u,document.baseURI).href;}catch(e){resolved=u;}"
                + "if(resolved.indexOf('/api/browser/proxy')!==-1){try{resolved=new URL(resolved,window.location.href).searchParams.get('url')||resolved;}catch(e){}}"
                + "try{"
                + "var cur=new URL(document.baseURI).href;"
                + "if(resolved===cur||resolved===cur.replace(/\\/$/,'')||resolved.replace(/\\/$/,'')===cur)return false;"
                + "}catch(e){}"
                + "try{_realTop.postMessage({type:'browser_navigate',url:resolved},'*');}catch(e){}"
                + "return true;"
                + "}"
                + "function proxyUrl(u){"
                + "if(u==null)return _lo+'/api/browser/empty';"
                + "if(typeof u!=='string')u=String(u);"
                + "if(!u||u==='undefined'||u==='null')return _lo+'/api/browser/empty';"
                + "if(u.indexOf('/api/browser/proxy')!==-1)return u;"
                + "if(u.indexOf('data:')===0)return u;"
                + "if(u.indexOf('blob:')===0)return u;"
                + "var r;"
                + "try{r=new URL(u,document.baseURI).href;}catch(e){return u;}"
                + "if(_bo&&_lo&&r.indexOf(_lo)===0){"
                + "r=_bo+r.substring(_lo.length);"
                + "}"
                + "return _lo+'/api/browser/proxy?url='+encodeURIComponent(r);"
                + "}"
                + "function shouldProxyAttr(name,value){"
                + "if(!_isDouyin||!name||!value)return false;"
                + "name=String(name).toLowerCase();"
                + "value=String(value);"
                + "if(value.indexOf('/api/browser/proxy')!==-1)return false;"
                + "if(value.indexOf('data:')===0||value.indexOf('blob:')===0||value.indexOf('javascript:')===0||value.indexOf('#')===0)return false;"
                + "return name==='src'||name==='href'||name==='poster'||name==='action'||name==='formaction';"
                + "}"
                + "var _setAttr=Element.prototype.setAttribute;"
                + "Element.prototype.setAttribute=function(name,value){"
                + "if(shouldProxyAttr(name,value)){value=proxyUrl(value);}"
                + "return _setAttr.call(this,name,value);"
                + "};"
                + "window.open=function(u){if(u){navigateTo(u);}return null;};"
                + "var _fetch=window.fetch;"
                + "window.fetch=function(input,init){"
                + "var u;"
                + "if(typeof input==='string'){u=input;}"
                + "else if(input instanceof Request){u=input.url;}"
                + "else if(input&&input.href){u=input.href;}"
                + "else{u=String(input);}"
                + "var pu=proxyUrl(u);"
                + "if(pu!==u){"
                + "if(typeof input==='string'){input=pu;}"
                + "else if(input instanceof Request){"
                + "var ni={};"
                + "if(init){for(var k in init)ni[k]=init[k];}"
                + "if(!ni.method)ni.method=input.method;"
                + "if(!ni.headers){try{ni.headers=Object.fromEntries(input.headers.entries());}catch(e){}}"
                + "if(!ni.body&&input.body)ni.body=input.body;"
                + "input=pu;init=ni;"
                + "}else{input=pu;}"
                + "}"
                + "return _fetch.call(this,input,init);"
                + "};"
                + "var _xhrOpen=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(m,u,a,user,pass){"
                + "var pu=proxyUrl(u);"
                + "return _xhrOpen.call(this,m,pu,a!==false,user,pass);"
                + "};"
                + "var _ES=window.EventSource;"
                + "if(_ES){"
                + "window.EventSource=function(u,opts){"
                + "var pu=proxyUrl(u);"
                + "return new _ES(pu,opts);"
                + "};"
                + "window.EventSource.prototype=_ES.prototype;"
                + "}"
                + "var _ps=history.pushState.bind(history);"
                + "history.pushState=function(s,t,u){"
                + "if(u){"
                + "try{"
                + "var r=new URL(u,document.baseURI).href;"
                + "if(_bo&&new URL(r).origin!==_bo){navigateTo(r);return;}"
                + "}catch(e){}"
                + "}"
                + "try{return _ps(s,t,u);}catch(e){}"
                + "};"
                + "var _rs=history.replaceState.bind(history);"
                + "history.replaceState=function(s,t,u){"
                + "if(u){"
                + "try{"
                + "var r=new URL(u,document.baseURI).href;"
                + "if(_bo&&new URL(r).origin!==_bo){return;}"
                + "}catch(e){}"
                + "}"
                + "try{return _rs(s,t,u);}catch(e){}"
                + "};"
                + "document.addEventListener('click',function(e){"
                + "var n=e.target;"
                + "var clearNode=n;"
                + "while(clearNode&&clearNode!==document.body){"
                + "var marker=((clearNode.className||'')+' '+(clearNode.id||'')+' '+(clearNode.title||'')+' '+(clearNode.getAttribute&&clearNode.getAttribute('aria-label')||'')).toLowerCase();"
                + "if(marker.indexOf('clear')!==-1||marker.indexOf('close')!==-1||marker.indexOf('delete')!==-1||marker.indexOf('清空')!==-1||marker.indexOf('删除')!==-1){"
                + "var root=clearNode.parentElement||document;"
                + "var input=root.querySelector&&root.querySelector('input[type=\"search\"],input[type=\"text\"],input:not([type])');"
                + "if(!input){input=document.querySelector('input[type=\"search\"],input[type=\"text\"],input:not([type])');}"
                + "if(input){input.value='';input.dispatchEvent(new Event('input',{bubbles:true}));input.dispatchEvent(new Event('change',{bubbles:true}));e.preventDefault();e.stopPropagation();return;}"
                + "}"
                + "clearNode=clearNode.parentNode;"
                + "}"
                + "while(n&&n.tagName!=='A'){n=n.parentNode;}"
                + "if(n&&n.href){"
                + "var href=n.href;"
                + "if(href.indexOf('javascript:')===0||href.indexOf('#')===0)return;"
                + "if(href.indexOf('/api/browser/proxy')!==-1){try{href=new URL(href,window.location.href).searchParams.get('url')||href;}catch(e){}}"
                + "e.preventDefault();e.stopPropagation();"
                + "navigateTo(href);"
                + "}"
                + "},true);"
                + "document.addEventListener('submit',function(e){"
                + "var f=e.target;"
                + "if(f&&f.tagName==='FORM'){"
                + "e.preventDefault();"
                + "var fd=new FormData(f);"
                + "var p=new URLSearchParams(fd).toString();"
                + "var u=f.action||window.location.href;"
                + "if(u.indexOf('/api/browser/proxy')!==-1){try{u=new URL(u,window.location.href).searchParams.get('url')||u;}catch(e){u=decodeURIComponent(u.split('url=')[1]||u);}}"
                + "var sep=u.indexOf('?')===-1?'?':'&';"
                + "navigateTo(u+sep+p);"
                + "}"
                + "},true);"
                + "new MutationObserver(function(ml){"
                + "ml.forEach(function(m){"
                + "m.addedNodes.forEach(function(n){"
                + "if(!n.tagName)return;"
                + "var tag=n.tagName.toUpperCase();"
                + "if((tag==='IFRAME'||(_isDouyin&&(tag==='SCRIPT'||tag==='LINK'||tag==='IMG'||tag==='SOURCE'||tag==='VIDEO'||tag==='AUDIO')))&&n.src){"
                + "var s=n.src;if(s.indexOf('about:')===0||s.indexOf('javascript:')===0||s.indexOf('data:')===0||s.indexOf('blob:')===0||s.indexOf('/api/browser/proxy')!==-1)return;n.src=proxyUrl(s);"
                + "}"
                + "if(_isDouyin&&tag==='LINK'&&n.href){"
                + "var h=n.href;if(h.indexOf('data:')===0||h.indexOf('blob:')===0||h.indexOf('/api/browser/proxy')!==-1)return;n.href=proxyUrl(h);"
                + "}"
                + "});"
                + "});"
                + "}).observe(document.documentElement,{childList:true,subtree:true});"
                + "})();</script>";

        html = removeCspMeta(html);
        html = html.replaceAll("(?i)(<a\\b[^>]*?)\\s+target=(\"_blank\"|'_blank'|_blank)", "$1 target=\"_self\"");
        html = html.replaceAll("(?i)(<area\\b[^>]*?)\\s+target=(\"_blank\"|'_blank'|_blank)", "$1 target=\"_self\"");
        html = html.replaceAll("(?i)(<form\\b[^>]*?)\\s+target=(\"_blank\"|'_blank'|_blank)", "$1 target=\"_self\"");
        html = html.replaceAll("if\\s*\\(\\s*parent\\s*!=\\s*self[^}]*\\}\\s*catch\\s*\\([^)]*\\)\\s*\\{[^}]*window\\.open[^}]*\\}", "");
        html = html.replaceAll("if\\s*\\(\\s*parent\\s*!=\\s*self[^}]*throw[^}]*\\}", "");
        html = html.replaceAll("top\\.location\\.href\\s*=\\s*[^;]+;", "");
        html = html.replaceAll("self\\.top\\.location\\s*=\\s*[^;]+;", "");
        html = html.replaceAll("(<script\\b[^>]*?)\\s+crossorigin(?:=\"[^\"]*\")?", "$1");
        html = html.replaceAll("(<link\\b[^>]*?)\\s+crossorigin(?:=\"[^\"]*\")?", "$1");
        html = html.replaceAll("(<script\\b[^>]*?)\\s+integrity=\"[^\"]*\"", "$1");
        html = html.replaceAll("(<link\\b[^>]*?)\\s+integrity=\"[^\"]*\"", "$1");
        html = html.replaceAll("(<script\\b[^>]*?)\\s+onerror=\"[^\"]*\"", "$1");

        html = rewriteIframeSrc(html, originalUrl);

        if (html.contains("<head>")) {
            html = html.replace("<head>", "<head>" + baseTag + script);
        } else if (html.contains("<HEAD>")) {
            html = html.replace("<HEAD>", "<HEAD>" + baseTag + script);
        } else {
            html = baseTag + script + html;
        }

        return html;
    }

    private String removeCspMeta(String html) {
        return html.replaceAll("(?is)<meta\\b(?=[^>]*http-equiv\\s*=\\s*(['\"]?)Content-Security-Policy\\1)[^>]*>", "");
    }

    private String rewriteIframeSrc(String html, String baseUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(<iframe\\b[^>]*?\\ssrc=)(\"([^\"]*)\"|'([^']*)')",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1);
            String quote = m.group(2).startsWith("\"") ? "\"" : "'";
            String src = m.group(3) != null ? m.group(3) : m.group(4);
            if (src == null || src.isEmpty() || src.startsWith("about:") || src.startsWith("javascript:")
                    || src.startsWith("data:") || src.startsWith("blob:") || src.indexOf("/api/browser/proxy") != -1) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + src + quote));
                continue;
            }
            String resolved;
            try {
                resolved = new URL(new URL(baseUrl), src).toString();
            } catch (Exception e) {
                resolved = src;
            }
            String proxied = "/api/browser/proxy?url=" + encodeURIComponent(resolved);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + proxied + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String rewriteDouyinResourceAttrs(String html, String baseUrl) {
        String rewritten = rewriteHtmlUrlAttr(html, baseUrl, "src");
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "href");
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "poster");
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "action");
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "formaction");
        return rewritten;
    }

    private String rewriteHtmlUrlAttr(String html, String baseUrl, String attrName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\s" + java.util.regex.Pattern.quote(attrName) + "\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1);
            String quote = m.group(2).startsWith("\"") ? "\"" : "'";
            String value = m.group(3) != null ? m.group(3) : m.group(4);
            if (value == null || value.isEmpty()
                    || value.startsWith("#")
                    || value.startsWith("data:")
                    || value.startsWith("blob:")
                    || value.startsWith("javascript:")
                    || value.contains("/api/browser/proxy")
                    || value.contains("/api/browser/empty")) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + value + quote));
                continue;
            }
            String resolved;
            try {
                resolved = new URL(new URL(baseUrl), value).toString();
            } catch (Exception e) {
                resolved = value;
            }
            String proxied = "/api/browser/proxy?url=" + encodeURIComponent(resolved);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + proxied + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private java.nio.charset.Charset resolveCharset(String contentType, byte[] body) {
        java.util.regex.Matcher headerMatcher = java.util.regex.Pattern
                .compile("charset=([^;\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(contentType == null ? "" : contentType);
        if (headerMatcher.find()) {
            return normalizeCharset(headerMatcher.group(1));
        }
        String prefix = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher metaMatcher = java.util.regex.Pattern
                .compile("charset=[\"']?([^\"'\\s/>]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(prefix);
        if (metaMatcher.find()) {
            return normalizeCharset(metaMatcher.group(1));
        }
        return StandardCharsets.UTF_8;
    }

    private java.nio.charset.Charset normalizeCharset(String charset) {
        String clean = charset == null ? "" : charset.trim().replace("\"", "").replace("'", "");
        try {
            if (!clean.isEmpty() && java.nio.charset.Charset.isSupported(clean)) {
                return java.nio.charset.Charset.forName(clean);
            }
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private List<Map<String, String>> parseBaiduResults(String html) {
        List<Map<String, String>> results = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)<h3[^>]*>\\s*<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>\\s*</h3>(.*?)(?=<h3|</body|$)"
        ).matcher(html == null ? "" : html);
        while (matcher.find() && results.size() < 12) {
            String title = stripHtml(matcher.group(2));
            String url = matcher.group(1);
            String snippet = stripHtml(matcher.group(3));
            if (!title.isBlank() && !url.isBlank()) {
                results.add(Map.of(
                        "title", title,
                        "url", url,
                        "snippet", snippet.length() > 180 ? snippet.substring(0, 180) : snippet
                ));
            }
        }
        return results;
    }

    private List<Map<String, String>> parseBilibiliResults(String json) {
        List<Map<String, String>> results = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?s)\\{[^{}]*\"arcurl\"\\s*:\\s*\".*?\"[^{}]*\\}"
        ).matcher(json == null ? "" : json);
        while (matcher.find() && results.size() < 20) {
            String block = matcher.group();
            String title = stripHtml(unescapeJson(extractJsonField(block, "title")));
            String snippet = stripHtml(unescapeJson(extractJsonField(block, "description")));
            String url = unescapeJson(extractJsonField(block, "arcurl"));
            if (!title.isBlank() && !url.isBlank()) {
                results.add(Map.of(
                        "title", title,
                        "url", url,
                        "snippet", snippet.length() > 180 ? snippet.substring(0, 180) : snippet
                ));
            }
        }
        return results;
    }

    private String extractJsonField(String json, String fieldName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(fieldName) + "\"\\s*:\\s*\"(.*?)\"",
                java.util.regex.Pattern.DOTALL
        ).matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String extractJsonString(String json, String fieldName) {
        return extractJsonField(json, fieldName);
    }

    private String extractJsonNumber(String json, String fieldName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"" + java.util.regex.Pattern.quote(fieldName) + "\"\\s*:\\s*(\\d+)"
        ).matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String stripHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String unescapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ");
    }

    private String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
