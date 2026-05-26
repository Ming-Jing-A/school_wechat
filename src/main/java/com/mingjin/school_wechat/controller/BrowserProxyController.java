package com.mingjin.school_wechat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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

@Slf4j
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
            } else if ("bing".equalsIgnoreCase(engine)) {
                targetUrl = "https://cn.bing.com/search?q=" + encodeURIComponent(keyword);
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
            List<Map<String, String>> results;
            if ("bilibili".equalsIgnoreCase(engine)) {
                results = parseBilibiliResults(text);
            } else if ("bing".equalsIgnoreCase(engine)) {
                results = parseBingHtmlResults(text);
            } else {
                results = parseBaiduResults(text);
            }
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
            String cid = extractVideoCid(json);
            String resolvedAid = aid.isEmpty() ? extractJsonNumber(json, "aid") : aid;
            String resolvedBvid = bvid.isEmpty() ? extractJsonString(json, "bvid") : bvid;
            String title = stripHtml(unescapeJson(extractJsonString(json, "title")));
            String pic = unescapeJson(extractJsonString(json, "pic"));
            if (cid.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法获取视频分 P 信息"));
            }

            String streamUrl = "";
            int[] qnLevels = {64, 32, 16};
            for (int qn : qnLevels) {
                String playUrl = "https://api.bilibili.com/x/player/playurl?"
                        + (!resolvedBvid.isEmpty() ? "bvid=" + encodeURIComponent(resolvedBvid) : "aid=" + encodeURIComponent(resolvedAid))
                        + "&cid=" + encodeURIComponent(cid)
                        + "&qn=" + qn + "&fnval=1&fourk=0";
                HttpRequest playReq = HttpRequest.newBuilder()
                        .uri(URI.create(playUrl))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Referer", "https://www.bilibili.com/")
                        .header("Accept", "application/json,*/*")
                        .GET()
                        .build();
                HttpResponse<byte[]> playResp = httpClient.send(playReq, HttpResponse.BodyHandlers.ofByteArray());
                String playJson = new String(playResp.body(), StandardCharsets.UTF_8);
                streamUrl = extractFirstPlayUrl(playJson);
                if (!streamUrl.isEmpty()) break;
            }

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", true);
            result.put("title", title);
            if (!pic.isEmpty()) {
                result.put("poster", buildProxyUrl(pic, ""));
            }
            if (!streamUrl.isEmpty()) {
                result.put("videoUrl", buildProxyUrl(streamUrl, ""));
                result.put("useIframe", false);
            } else {
                StringBuilder playerUrl = new StringBuilder("https://player.bilibili.com/player.html?isOutside=true");
                if (!resolvedBvid.isEmpty()) {
                    playerUrl.append("&bvid=").append(encodeURIComponent(resolvedBvid));
                }
                if (!resolvedAid.isEmpty()) {
                    playerUrl.append("&aid=").append(encodeURIComponent(resolvedAid));
                }
                playerUrl.append("&cid=").append(encodeURIComponent(cid))
                        .append("&p=1&autoplay=1&high_quality=1");
                result.put("playerUrl", playerUrl.toString());
                result.put("useIframe", true);
            }
            result.put("pageUrl", url);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", ex.getMessage() == null ? "视频加载失败" : ex.getMessage()
            ));
        }
    }

    @RequestMapping(value = "/bilibili-play-page", method = RequestMethod.GET, produces = "text/html;charset=UTF-8")
    public ResponseEntity<byte[]> bilibiliPlayPage(@RequestParam String url,
                                                    @RequestParam(value = "p", defaultValue = "1") int page) {
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
                return ResponseEntity.ok().header("Content-Type", "text/html;charset=UTF-8")
                        .body(buildErrorPage("无法识别视频ID").getBytes(StandardCharsets.UTF_8));
            }

            String viewUrl = "https://api.bilibili.com/x/web-interface/view?"
                    + (!bvid.isEmpty() ? "bvid=" + encodeURIComponent(bvid) : "aid=" + encodeURIComponent(aid));
            HttpRequest viewReq = HttpRequest.newBuilder()
                    .uri(URI.create(viewUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> viewResp = httpClient.send(viewReq, HttpResponse.BodyHandlers.ofByteArray());
            String viewJson = new String(viewResp.body(), StandardCharsets.UTF_8);
            String resolvedAid = aid.isEmpty() ? extractJsonNumber(viewJson, "aid") : aid;
            String resolvedBvid = bvid.isEmpty() ? extractJsonString(viewJson, "bvid") : bvid;
            String title = stripHtml(unescapeJson(extractJsonString(viewJson, "title")));
            String pic = unescapeJson(extractJsonString(viewJson, "pic"));

            List<Map<String, String>> pagesList = extractVideoPages(viewJson);
            if (page < 1 || page > pagesList.size()) {
                page = 1;
            }
            String cid = pagesList.isEmpty() ? extractVideoCid(viewJson) : pagesList.get(page - 1).get("cid");

            String videoStreamUrl = "";
            if (!cid.isEmpty()) {
                int[] qnLevels = {64, 32, 16};
                for (int qn : qnLevels) {
                    String playUrl = "https://api.bilibili.com/x/player/playurl?"
                            + (!resolvedBvid.isEmpty() ? "bvid=" + encodeURIComponent(resolvedBvid) : "aid=" + encodeURIComponent(resolvedAid))
                            + "&cid=" + encodeURIComponent(cid)
                            + "&qn=" + qn + "&fnval=1&fourk=0";
                    log.info("Requesting Bilibili playurl with qn={}", qn);
                    HttpRequest playReq = HttpRequest.newBuilder()
                            .uri(URI.create(playUrl))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.bilibili.com/")
                            .header("Accept", "application/json,*/*")
                            .GET()
                            .build();
                    HttpResponse<byte[]> playResp = httpClient.send(playReq, HttpResponse.BodyHandlers.ofByteArray());
                    String playJson = new String(playResp.body(), StandardCharsets.UTF_8);
                    videoStreamUrl = extractFirstPlayUrl(playJson);
                    log.info("Extracted video stream URL: {}", videoStreamUrl.isEmpty() ? "(empty)" : videoStreamUrl);
                    if (!videoStreamUrl.isEmpty()) break;
                }
            }

            String proxyVideoUrl = videoStreamUrl.isEmpty() ? "" : buildProxyUrl(videoStreamUrl, "");
            log.info("Final proxy video URL: {}", proxyVideoUrl.isEmpty() ? "(empty)" : proxyVideoUrl);
            String posterUrl = pic.isEmpty() ? "" : buildProxyUrl(pic, "");

            java.util.Map<String, String> epStreamMap = new java.util.LinkedHashMap<>();
            if (!pagesList.isEmpty()) {
                for (int pi = 0; pi < pagesList.size(); pi++) {
                    String epCid = pagesList.get(pi).get("cid");
                    String epPage = pagesList.get(pi).get("page");
                    if (pi == page - 1) {
                        epStreamMap.put(epPage, proxyVideoUrl);
                    } else {
                        String epStreamUrl = "";
                        if (!epCid.isEmpty()) {
                            int[] qnLevels = {64, 32, 16};
                            for (int qn : qnLevels) {
                                String playUrl2 = "https://api.bilibili.com/x/player/playurl?"
                                        + (!resolvedBvid.isEmpty() ? "bvid=" + encodeURIComponent(resolvedBvid) : "aid=" + encodeURIComponent(resolvedAid))
                                        + "&cid=" + encodeURIComponent(epCid)
                                        + "&qn=" + qn + "&fnval=1&fourk=0";
                                try {
                                    HttpRequest playReq2 = HttpRequest.newBuilder()
                                            .uri(URI.create(playUrl2))
                                            .timeout(Duration.ofSeconds(10))
                                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                                            .header("Referer", "https://www.bilibili.com/")
                                            .header("Accept", "application/json,*/*")
                                            .GET()
                                            .build();
                                    HttpResponse<byte[]> playResp2 = httpClient.send(playReq2, HttpResponse.BodyHandlers.ofByteArray());
                                    String playJson2 = new String(playResp2.body(), StandardCharsets.UTF_8);
                                    epStreamUrl = extractFirstPlayUrl(playJson2);
                                    if (!epStreamUrl.isEmpty()) break;
                                } catch (Exception ex2) {
                                    log.warn("Failed to get stream for page {}: {}", epPage, ex2.getMessage());
                                }
                            }
                        }
                        epStreamMap.put(epPage, epStreamUrl.isEmpty() ? "" : buildProxyUrl(epStreamUrl, ""));
                    }
                }
            }

            String html = buildBilibiliPlayerHtml(title, proxyVideoUrl, posterUrl, url, resolvedBvid, resolvedAid, pagesList, page, epStreamMap);
            return ResponseEntity.ok().header("Content-Type", "text/html;charset=UTF-8")
                    .body(html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return ResponseEntity.ok().header("Content-Type", "text/html;charset=UTF-8")
                    .body(buildErrorPage(ex.getMessage() != null ? ex.getMessage() : "视频加载失败").getBytes(StandardCharsets.UTF_8));
        }
    }

    @RequestMapping(value = "/bilibili-video-stream", method = RequestMethod.GET, produces = "application/json;charset=UTF-8")
    public ResponseEntity<byte[]> bilibiliVideoStream(
            @RequestParam String bvid,
            @RequestParam String cid,
            @RequestParam(defaultValue = "64") int qn) {
        try {
            String playUrl = "https://api.bilibili.com/x/player/playurl?"
                    + "bvid=" + encodeURIComponent(bvid)
                    + "&cid=" + encodeURIComponent(cid)
                    + "&qn=" + qn + "&fnval=1&fourk=0";
            HttpRequest playReq = HttpRequest.newBuilder()
                    .uri(URI.create(playUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> playResp = httpClient.send(playReq, HttpResponse.BodyHandlers.ofByteArray());
            String playJson = new String(playResp.body(), StandardCharsets.UTF_8);
            String streamUrl = extractFirstPlayUrl(playJson);
            String proxyUrl = streamUrl.isEmpty() ? "" : buildProxyUrl(streamUrl, "");
            String json = "{\"url\":\"" + escapeJs(proxyUrl) + "\",\"success\":" + (!proxyUrl.isEmpty()) + "}";
            return ResponseEntity.ok().header("Content-Type", "application/json;charset=UTF-8")
                    .body(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            String json = "{\"url\":\"\",\"success\":false,\"error\":\"" + escapeJs(ex.getMessage() != null ? ex.getMessage() : "获取失败") + "\"}";
            return ResponseEntity.ok().header("Content-Type", "application/json;charset=UTF-8")
                    .body(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private List<Map<String, String>> extractVideoPages(String viewJson) {
        List<Map<String, String>> pages = new java.util.ArrayList<>();
        if (viewJson == null || viewJson.isEmpty()) return pages;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(viewJson);
            com.fasterxml.jackson.databind.JsonNode dataNode = root.has("data") ? root.get("data") : root;
            com.fasterxml.jackson.databind.JsonNode pagesNode = dataNode.has("pages") ? dataNode.get("pages") : null;
            if (pagesNode != null && pagesNode.isArray()) {
                int pageNum = 1;
                for (com.fasterxml.jackson.databind.JsonNode pageNode : pagesNode) {
                    String cid = pageNode.has("cid") ? pageNode.get("cid").asText() : "";
                    String part = pageNode.has("part") ? pageNode.get("part").asText() : "";
                    if (part.isEmpty()) {
                        part = "P" + pageNum;
                    }
                    if (!cid.isEmpty()) {
                        pages.add(Map.of("cid", cid, "part", part, "page", String.valueOf(pageNum)));
                    }
                    pageNum++;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse pages with Jackson, falling back to regex: {}", e.getMessage());
            java.util.regex.Matcher pagesArrayMatcher = java.util.regex.Pattern.compile(
                    "\"pages\"\\s*:\\s*\\[", java.util.regex.Pattern.DOTALL
            ).matcher(viewJson);
            if (!pagesArrayMatcher.find()) return pages;
            int start = pagesArrayMatcher.end();
            int depth = 1;
            int end = start;
            while (end < viewJson.length() && depth > 0) {
                char c = viewJson.charAt(end);
                if (c == '[') depth++;
                else if (c == ']') depth--;
                end++;
            }
            String pagesArray = viewJson.substring(start, end - 1);
            java.util.regex.Matcher itemMatcher = java.util.regex.Pattern.compile(
                    "\"cid\"\\s*:\\s*(\\d+)"
            ).matcher(pagesArray);
            java.util.regex.Matcher partMatcher = java.util.regex.Pattern.compile(
                    "\"part\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
            ).matcher(pagesArray);
            List<String> cids = new java.util.ArrayList<>();
            List<String> parts = new java.util.ArrayList<>();
            while (itemMatcher.find()) {
                cids.add(itemMatcher.group(1));
            }
            while (partMatcher.find()) {
                parts.add(unescapeJson(partMatcher.group(1)));
            }
            int count = Math.max(cids.size(), parts.size());
            for (int i = 0; i < count; i++) {
                String cid = i < cids.size() ? cids.get(i) : "";
                String part = i < parts.size() ? parts.get(i) : ("P" + (i + 1));
                if (!cid.isEmpty()) {
                    pages.add(Map.of("cid", cid, "part", part, "page", String.valueOf(i + 1)));
                }
            }
        }
        return pages;
    }

    private String buildBilibiliPlayerHtml(String title, String videoUrl, String posterUrl, String pageUrl,
                                            String bvid, String aid, List<Map<String, String>> pagesList, int currentPage,
                                            java.util.Map<String, String> epStreamMap) {
        String safeTitle = escapeHtml(title != null ? title : "哔哩哔哩视频");
        String safeVideoUrl = escapeHtml(videoUrl != null ? videoUrl : "");
        String safePosterUrl = escapeHtml(posterUrl != null ? posterUrl : "");
        String safePageUrl = escapeHtml(pageUrl != null ? pageUrl : "");
        String safeBvid = escapeJs(bvid != null ? bvid : "");
        String safeAid = escapeJs(aid != null ? aid : "");
        boolean hasMultiPages = pagesList != null && pagesList.size() > 1;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<style>");
        sb.append(":root{--brand_pink:#FF6699;--brand_blue:#00AEEC;--bg1:#fff;--bg2:#f6f7f8;--bg3:#f1f2f3;--text1:#18191c;--text2:#61666d;--text3:#9499a0;--line_regular:#e3e5e7}");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}");
        sb.append("body{background:#000;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Helvetica Neue,Helvetica,Arial,PingFang SC,Microsoft YaHei,sans-serif;overflow:hidden;height:100vh}");
        sb.append(".header{display:flex;align-items:center;padding:10px 16px;background:#212121;gap:12px}");
        sb.append(".back-btn{background:none;border:1px solid #555;color:#aaa;padding:4px 12px;border-radius:4px;cursor:pointer;font-size:13px;flex-shrink:0}");
        sb.append(".back-btn:hover{border-color:#999;color:#fff}");
        sb.append(".header h1{font-size:15px;font-weight:500;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}");
        sb.append(".toggle-ep{background:none;border:1px solid var(--brand_pink);color:var(--brand_pink);padding:4px 12px;border-radius:4px;cursor:pointer;font-size:13px;flex-shrink:0;display:none}");
        sb.append(".toggle-ep:hover{background:var(--brand_pink);color:#fff}");
        sb.append(".main{display:flex;height:calc(100vh - 44px);background:#000}");
        sb.append(".video-area{flex:1;display:flex;justify-content:center;align-items:center;background:#000;position:relative;min-width:0}");
        sb.append("video{max-width:100%;max-height:100%;width:100%;height:100%;object-fit:contain}");
        sb.append(".ep-sidebar{width:280px;background:#1a1a1a;border-left:1px solid #2a2a2a;flex-direction:column;flex-shrink:0;overflow:hidden}");
        sb.append(".ep-sidebar.hidden{display:none}");
        sb.append(".ep-sidebar.visible{display:flex}");
        sb.append(".ep-header{padding:12px 16px;font-size:14px;font-weight:500;color:#eee;border-bottom:1px solid #2a2a2a;flex-shrink:0}");
        sb.append(".ep-list{flex:1;overflow-y:auto;padding:4px 0}");
        sb.append(".ep-list::-webkit-scrollbar{width:4px}");
        sb.append(".ep-list::-webkit-scrollbar-thumb{background:#444;border-radius:2px}");
        sb.append(".ep-item{padding:10px 16px;font-size:13px;color:#aaa;cursor:pointer;transition:all .15s;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}");
        sb.append(".ep-item:hover{background:#2a2a2a;color:#eee}");
        sb.append(".ep-item.active{color:var(--brand_pink);background:#2a1a22}");
        sb.append(".ep-item.active::before{content:'';display:inline-block;width:3px;height:14px;background:var(--brand_pink);border-radius:2px;margin-right:8px;vertical-align:middle}");
        sb.append(".error{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;color:#999;width:100%}");
        sb.append(".error p{margin:8px 0;font-size:14px}");
        sb.append(".retry-btn{margin-top:16px;padding:8px 24px;background:var(--brand_pink);color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:14px}");
        sb.append(".retry-btn:hover{background:#e85689}");
        sb.append(".loading-overlay{position:absolute;top:0;left:0;width:100%;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;background:rgba(0,0,0,.7);z-index:10;opacity:0;pointer-events:none;transition:opacity .2s}");
        sb.append(".loading-overlay.show{opacity:1;pointer-events:auto}");
        sb.append(".spinner{width:36px;height:36px;border:3px solid #333;border-top-color:var(--brand_pink);border-radius:50%;animation:spin .8s linear infinite}");
        sb.append("@keyframes spin{to{transform:rotate(360deg)}}");
        sb.append("</style></head><body>");

        sb.append("<div class='header'>");
        sb.append("<button class='back-btn' onclick='goBack()'>&#8592; 返回</button>");
        sb.append("<h1 id='videoTitle'>").append(safeTitle).append("</h1>");
        if (hasMultiPages) {
            sb.append("<button class='toggle-ep' id='toggleEpBtn' style='display:block' onclick='toggleEp()'>选集</button>");
        }
        sb.append("</div>");

        sb.append("<div class='main'>");
        sb.append("<div class='video-area' id='videoArea'>");
        if (safeVideoUrl.isEmpty()) {
            sb.append("<div class='error'><p>无法获取视频流</p><p style='font-size:12px;color:#666'>可能需要登录或视频不可用</p><button class='retry-btn' onclick='retryWithPlayer()'>使用B站播放器</button></div>");
        } else {
            sb.append("<video id='player' controls playsinline autoplay preload='auto'");
            if (!safePosterUrl.isEmpty()) sb.append(" poster='").append(safePosterUrl).append("'");
            sb.append(" src='").append(safeVideoUrl).append("'></video>");
        }
        sb.append("<div class='loading-overlay' id='loadingOverlay'><div class='spinner'></div></div>");
        sb.append("</div>");

        if (hasMultiPages) {
            sb.append("<div class='ep-sidebar visible' id='epSidebar'>");
            sb.append("<div class='ep-header'>选集 (").append(pagesList.size()).append(")</div>");
            sb.append("<div class='ep-list' id='epList'>");
            for (int i = 0; i < pagesList.size(); i++) {
                Map<String, String> pg = pagesList.get(i);
                String partTitle = escapeHtml(pg.get("part"));
                String cid = pg.get("cid");
                String pgNum = pg.get("page");
                boolean isActive = (i + 1) == currentPage;
                sb.append("<div class='ep-item").append(isActive ? " active" : "").append("' data-cid='").append(cid).append("' data-page='").append(pgNum).append("' data-part='").append(escapeHtml(pg.get("part"))).append("' onclick='switchEp(this)'>");
                sb.append("P").append(pgNum).append(" ").append(partTitle);
                sb.append("</div>");
            }
            sb.append("</div>");
            sb.append("</div>");
        }

        sb.append("</div>");

        sb.append("<script>");
        sb.append("var bvid='").append(safeBvid).append("';");
        sb.append("var aid='").append(safeAid).append("';");
        sb.append("var currentPage=").append(currentPage).append(";");
        sb.append("var epVisible=true;");
        sb.append("var epStreams={");
        if (epStreamMap != null && !epStreamMap.isEmpty()) {
            boolean first = true;
            for (java.util.Map.Entry<String, String> entry : epStreamMap.entrySet()) {
                if (!first) sb.append(",");
                sb.append("'").append(escapeJs(entry.getKey())).append("':'").append(escapeJs(entry.getValue())).append("'");
                first = false;
            }
        }
        sb.append("};");

        sb.append("function goBack(){try{window.parent.postMessage({type:'browser_go_back'},'*');}catch(e){try{history.back();}catch(e2){}}}");

        sb.append("function toggleEp(){var sb=document.getElementById('epSidebar');epVisible=!epVisible;if(epVisible){sb.classList.remove('hidden');sb.classList.add('visible');}else{sb.classList.remove('visible');sb.classList.add('hidden');}}");

        sb.append("function switchEp(el){");
        sb.append("var pg=el.getAttribute('data-page');");
        sb.append("var part=el.getAttribute('data-part');");
        sb.append("var items=document.querySelectorAll('.ep-item');");
        sb.append("for(var i=0;i<items.length;i++){items[i].classList.remove('active');}");
        sb.append("el.classList.add('active');");
        sb.append("currentPage=parseInt(pg);");
        sb.append("document.getElementById('videoTitle').textContent=part||('P'+pg);");
        sb.append("var streamUrl=epStreams[pg]||'';");
        sb.append("if(streamUrl){");
        sb.append("var player=document.getElementById('player');");
        sb.append("if(!player){");
        sb.append("var area=document.getElementById('videoArea');");
        sb.append("area.innerHTML='<video id=\"player\" controls playsinline autoplay preload=\"auto\" src=\"'+streamUrl+'\"></video><div class=\"loading-overlay\" id=\"loadingOverlay\"><div class=\"spinner\"></div></div>';");
        sb.append("player=document.getElementById('player');");
        sb.append("}");
        sb.append("player.src=streamUrl;");
        sb.append("player.play().catch(function(){});");
        sb.append("}else{");
        sb.append("retryWithPlayer();");
        sb.append("}");
        sb.append("}");

        sb.append("function showLoading(show){var ol=document.getElementById('loadingOverlay');if(ol){if(show){ol.classList.add('show');}else{ol.classList.remove('show');}}}");

        sb.append("function retryWithPlayer(){");
        sb.append("var area=document.getElementById('videoArea');");
        sb.append("var iframe=document.createElement('iframe');");
        sb.append("iframe.style.cssText='width:100%;height:100%;border:none';");
        sb.append("iframe.allow='autoplay;fullscreen;encrypted-media;picture-in-picture';");
        sb.append("iframe.setAttribute('allowfullscreen','');");
        sb.append("var q=bvid?'bvid='+encodeURIComponent(bvid):'aid='+encodeURIComponent(aid);");
        sb.append("iframe.src='/api/browser/proxy?url='+encodeURIComponent('https://player.bilibili.com/player.html?isOutside=true&'+q+'&p='+currentPage+'&autoplay=1&high_quality=1');");
        sb.append("area.innerHTML='';area.appendChild(iframe);");
        sb.append("}");

        sb.append("var player=document.getElementById('player');");
        sb.append("if(player){");
        sb.append("player.addEventListener('error',function(){");
        sb.append("var area=document.getElementById('videoArea');");
        sb.append("area.innerHTML='<div class=\"error\"><p>视频加载失败</p><button class=\"retry-btn\" onclick=\"retryWithPlayer()\">使用B站播放器</button></div>';");
        sb.append("});");
        sb.append("player.addEventListener('canplay',function(){try{player.play();}catch(e){}});");
        sb.append("player.addEventListener('loadeddata',function(){try{player.play();}catch(e){}});");
        sb.append("try{player.play();}catch(e){}");
        sb.append("}");
        sb.append("</script></body></html>");
        return sb.toString();
    }

    private String buildErrorPage(String message) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<style>body{display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:system-ui;color:#999;background:#000}"
                + "p{font-size:14px}</style></head>"
                + "<body><p>" + escapeHtml(message) + "</p></body></html>";
    }

    @RequestMapping(value = "/stream", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void streamProxy(
            @RequestParam String url,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            log.info("Stream proxy request received for URL: {}", url);
            log.info("Client IP: {}, Method: {}", servletRequest.getRemoteAddr(), servletRequest.getMethod());
            log.info("Range header: {}", servletRequest.getHeader("Range"));
            
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            String referer;
            String origin;
            if (isBilibiliVideoCdnUrl(url)) {
                referer = "https://www.bilibili.com/";
                origin = "https://www.bilibili.com";
            } else {
                referer = extractReferer(url);
                origin = extractOrigin(url);
            }
            reqBuilder.header("Referer", referer);
            reqBuilder.header("Origin", origin);

            String serverCookies = getCookiesForUri(URI.create(url));
            if (!serverCookies.isEmpty()) {
                reqBuilder.header("Cookie", serverCookies);
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

            if ("HEAD".equalsIgnoreCase(servletRequest.getMethod())) {
                reqBuilder.method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody());
            } else {
                reqBuilder.GET();
            }

            java.net.http.HttpRequest httpRequest = reqBuilder.build();

            java.net.http.HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            int statusCode = response.statusCode();
            servletResponse.setStatus(statusCode);

            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            servletResponse.setContentType(contentType);

            List<String> setCookies = response.headers().allValues("Set-Cookie");
            if (!setCookies.isEmpty()) {
                storeCookies(URI.create(url), setCookies);
            }

            response.headers().allValues("Content-Length").forEach(v -> servletResponse.addHeader("Content-Length", v));
            response.headers().allValues("Content-Range").forEach(v -> servletResponse.addHeader("Content-Range", v));
            response.headers().allValues("Accept-Ranges").forEach(v -> servletResponse.addHeader("Accept-Ranges", v));
            response.headers().allValues("ETag").forEach(v -> servletResponse.addHeader("ETag", v));
            response.headers().allValues("Last-Modified").forEach(v -> servletResponse.addHeader("Last-Modified", v));
            response.headers().allValues("Cache-Control").forEach(v -> servletResponse.addHeader("Cache-Control", v));
            response.headers().allValues("Age").forEach(v -> servletResponse.addHeader("Age", v));

            servletResponse.addHeader("Access-Control-Allow-Origin", "*");
            servletResponse.addHeader("Access-Control-Allow-Credentials", "true");
            servletResponse.addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, ETag, Last-Modified");

            if ("HEAD".equalsIgnoreCase(servletRequest.getMethod())) {
                return;
            }

            if (statusCode == 304) {
                return;
            }

            try (java.io.InputStream is = response.body();
                 java.io.OutputStream os = servletResponse.getOutputStream()) {
                byte[] buffer = new byte[65536];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                    os.flush();
                }
            }
        } catch (Exception e) {
            try {
                servletResponse.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                servletResponse.setContentType("text/plain;charset=UTF-8");
                servletResponse.getWriter().write("Stream proxy error: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    @RequestMapping(value = "/bilibili-stream", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> bilibiliStream(@RequestParam String url) {
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

            String viewUrl = "https://api.bilibili.com/x/web-interface/view?"
                    + (!bvid.isEmpty() ? "bvid=" + encodeURIComponent(bvid) : "aid=" + encodeURIComponent(aid));
            HttpRequest viewReq = HttpRequest.newBuilder()
                    .uri(URI.create(viewUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> viewResp = httpClient.send(viewReq, HttpResponse.BodyHandlers.ofByteArray());
            String viewJson = new String(viewResp.body(), StandardCharsets.UTF_8);
            if (!viewJson.contains("\"code\":0")) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法获取视频信息"));
            }

            String cid = extractVideoCid(viewJson);
            String resolvedAid = aid.isEmpty() ? extractJsonNumber(viewJson, "aid") : aid;
            String resolvedBvid = bvid.isEmpty() ? extractJsonString(viewJson, "bvid") : bvid;
            String title = stripHtml(unescapeJson(extractJsonString(viewJson, "title")));
            String pic = unescapeJson(extractJsonString(viewJson, "pic"));
            if (cid.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "无法获取视频分 P 信息"));
            }

            String playUrl = "https://api.bilibili.com/x/player/playurl?"
                    + (!resolvedBvid.isEmpty() ? "bvid=" + encodeURIComponent(resolvedBvid) : "aid=" + encodeURIComponent(resolvedAid))
                    + "&cid=" + encodeURIComponent(cid)
                    + "&qn=64&fnval=1&fourk=1";
            HttpRequest playReq = HttpRequest.newBuilder()
                    .uri(URI.create(playUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> playResp = httpClient.send(playReq, HttpResponse.BodyHandlers.ofByteArray());
            String playJson = new String(playResp.body(), StandardCharsets.UTF_8);
            String streamUrl = extractFirstPlayUrl(playJson);
            if (streamUrl.isEmpty()) {
                String dashPlayUrl = "https://api.bilibili.com/x/player/playurl?"
                        + (!resolvedBvid.isEmpty() ? "bvid=" + encodeURIComponent(resolvedBvid) : "aid=" + encodeURIComponent(resolvedAid))
                        + "&cid=" + encodeURIComponent(cid)
                        + "&qn=32&fnval=1&fourk=0";
                HttpRequest dashReq = HttpRequest.newBuilder()
                        .uri(URI.create(dashPlayUrl))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Referer", "https://www.bilibili.com/")
                        .header("Accept", "application/json,*/*")
                        .GET()
                        .build();
                HttpResponse<byte[]> dashResp = httpClient.send(dashReq, HttpResponse.BodyHandlers.ofByteArray());
                String dashJson = new String(dashResp.body(), StandardCharsets.UTF_8);
                streamUrl = extractFirstPlayUrl(dashJson);
            }
            if (streamUrl.isEmpty()) {
                StringBuilder playerUrl = new StringBuilder("https://player.bilibili.com/player.html?isOutside=true");
                if (!resolvedBvid.isEmpty()) {
                    playerUrl.append("&bvid=").append(encodeURIComponent(resolvedBvid));
                }
                if (!resolvedAid.isEmpty()) {
                    playerUrl.append("&aid=").append(encodeURIComponent(resolvedAid));
                }
                playerUrl.append("&cid=").append(encodeURIComponent(cid))
                        .append("&p=1&autoplay=1&high_quality=1");
                java.util.Map<String, Object> fallback = new java.util.LinkedHashMap<>();
                fallback.put("success", true);
                fallback.put("title", title);
                fallback.put("playerUrl", playerUrl.toString());
                fallback.put("useIframe", true);
                if (!pic.isEmpty()) {
                    fallback.put("poster", buildProxyUrl(pic, ""));
                }
                fallback.put("pageUrl", url);
                return ResponseEntity.ok(fallback);
            }

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("success", true);
            result.put("title", title);
            result.put("videoUrl", buildProxyUrl(streamUrl, ""));
            result.put("useIframe", false);
            if (!pic.isEmpty()) {
                result.put("poster", buildProxyUrl(pic, ""));
            }
            result.put("pageUrl", url);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", ex.getMessage() == null ? "视频加载失败" : ex.getMessage()
            ));
        }
    }

    @RequestMapping(value = "/bilibili-search", method = RequestMethod.GET, produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> bilibiliSearch(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "25") int pageSize) {
        try {
            String apiUrl = "https://api.bilibili.com/x/web-interface/search/type?keyword="
                    + encodeURIComponent(keyword)
                    + "&page=" + page
                    + "&page_size=" + pageSize
                    + "&search_type=video";
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://search.bilibili.com/")
                    .timeout(java.time.Duration.ofSeconds(15))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            String json = response.body();
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode data = root.get("data");
            com.fasterxml.jackson.databind.JsonNode resultArr = (data != null && data.has("result")) ? data.get("result") : null;
            int numResults = (data != null && data.has("numResults")) ? data.get("numResults").asInt() : 0;
            int totalPages = (int) Math.ceil((double) numResults / pageSize);
            if (totalPages < 1) totalPages = 1;

            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
            sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
            sb.append("<title>B站搜索: ").append(escapeHtml(keyword)).append(" - 第").append(page).append("页</title>");
            sb.append("<style>");
            sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
            sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#f4f5f7;color:#333;}");
            sb.append(".header{background:#fff;padding:16px 20px;border-bottom:1px solid #e3e5e7;position:sticky;top:0;z-index:10;}");
            sb.append(".header h2{font-size:18px;color:#18191c;}");
            sb.append(".header .info{font-size:13px;color:#9499a0;margin-top:4px;}");
            sb.append(".results{max-width:900px;margin:0 auto;padding:16px;}");
            sb.append(".video-card{display:flex;background:#fff;border-radius:8px;overflow:hidden;margin-bottom:12px;cursor:pointer;transition:box-shadow .2s;border:1px solid #e3e5e7;}");
            sb.append(".video-card:hover{box-shadow:0 2px 12px rgba(0,0,0,.1);}");
            sb.append(".video-card .cover{width:200px;min-width:200px;height:125px;overflow:hidden;position:relative;flex-shrink:0;}");
            sb.append(".video-card .cover img{width:100%;height:100%;object-fit:cover;}");
            sb.append(".video-card .cover .duration{position:absolute;bottom:4px;right:4px;background:rgba(0,0,0,.7);color:#fff;font-size:11px;padding:1px 4px;border-radius:2px;}");
            sb.append(".video-card .info{padding:12px 16px;display:flex;flex-direction:column;justify-content:space-between;flex:1;min-width:0;}");
            sb.append(".video-card .title{font-size:14px;line-height:1.4;color:#18191c;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}");
            sb.append(".video-card .title em{color:#f25d8e;font-style:normal;}");
            sb.append(".video-card .meta{font-size:12px;color:#9499a0;display:flex;gap:16px;flex-wrap:wrap;}");
            sb.append(".video-card .meta span{display:flex;align-items:center;gap:3px;}");
            sb.append(".video-card .author{font-size:12px;color:#9499a0;}");
            sb.append(".pagination{display:flex;justify-content:center;align-items:center;gap:8px;padding:24px 0;}");
            sb.append(".pagination button{border:none;background:#00a1d6;color:#fff;padding:8px 16px;border-radius:4px;cursor:pointer;font-size:14px;}");
            sb.append(".pagination button:disabled{background:#e3e5e7;color:#9499a0;cursor:not-allowed;}");
            sb.append(".pagination .page-info{font-size:14px;color:#61666d;}");
            sb.append(".empty{text-align:center;padding:60px 20px;color:#9499a0;font-size:15px;}");
            sb.append("</style></head><body>");
            sb.append("<div class='header'>");
            sb.append("<h2>B站搜索: ").append(escapeHtml(keyword)).append("</h2>");
            sb.append("<div class='info'>找到约 ").append(numResults).append(" 个结果 · 第 ").append(page).append("/").append(totalPages).append(" 页</div>");
            sb.append("</div>");
            sb.append("<div class='results'>");

            if (resultArr != null && resultArr.isArray() && resultArr.size() > 0) {
                for (com.fasterxml.jackson.databind.JsonNode item : resultArr) {
                    String title = item.has("title") ? item.get("title").asText() : "";
                    String author = item.has("author") ? item.get("author").asText() : "";
                    String pic = item.has("pic") ? item.get("pic").asText() : "";
                    String bvid = item.has("bvid") ? item.get("bvid").asText() : "";
                    String arcurl = item.has("arcurl") ? item.get("arcurl").asText() : "";
                    long play = item.has("play") ? item.get("play").asLong() : 0;
                    long danmaku = item.has("video_review") ? item.get("video_review").asLong() : 0;
                    String duration = item.has("duration") ? item.get("duration").asText() : "";
                    String description = item.has("description") ? item.get("description").asText() : "";

                    title = title.replaceAll("<em class=\"keyword\">", "<em>").replaceAll("</em>", "</em>");

                    if (!pic.startsWith("http")) {
                        pic = "https:" + pic;
                    }
                    String proxyPic = buildProxyUrl(pic, "");
                    String videoUrl = arcurl.isEmpty() ? "https://www.bilibili.com/video/" + bvid : arcurl;

                    sb.append("<div class='video-card' onclick=\"window.parent.postMessage({type:'browser_navigate',url:'")
                      .append(escapeJs(videoUrl)).append("'},'*')\">");
                    sb.append("<div class='cover'>");
                    sb.append("<img src='").append(proxyPic).append("' alt='' loading='lazy'>");
                    if (!duration.isEmpty()) {
                        sb.append("<div class='duration'>").append(escapeHtml(duration)).append("</div>");
                    }
                    sb.append("</div>");
                    sb.append("<div class='info'>");
                    sb.append("<div class='title'>").append(title).append("</div>");
                    sb.append("<div class='author'>UP: ").append(escapeHtml(author)).append("</div>");
                    sb.append("<div class='meta'>");
                    sb.append("<span>▶ ").append(formatCount(play)).append("</span>");
                    sb.append("<span>💬 ").append(formatCount(danmaku)).append("</span>");
                    sb.append("</div>");
                    sb.append("</div>");
                    sb.append("</div>");
                }
            } else {
                sb.append("<div class='empty'>没有找到相关视频</div>");
            }

            sb.append("</div>");
            sb.append("<div class='pagination'>");
            if (page > 1) {
                sb.append("<button onclick=\"loadPage(").append(page - 1).append(")\">上一页</button>");
            } else {
                sb.append("<button disabled>上一页</button>");
            }
            sb.append("<span class='page-info'>第 ").append(page).append(" / ").append(totalPages).append(" 页</span>");
            if (page < totalPages) {
                sb.append("<button onclick=\"loadPage(").append(page + 1).append(")\">下一页</button>");
            } else {
                sb.append("<button disabled>下一页</button>");
            }
            sb.append("</div>");

            sb.append("<script>");
            sb.append("var _keyword='").append(escapeJs(keyword)).append("';");
            sb.append("var _page=").append(page).append(";");
            sb.append("function loadPage(p){window.parent.postMessage({type:'browser_navigate',url:'https://search.bilibili.com/all?keyword='+encodeURIComponent(_keyword)+'&page='+p},'*');}");
            sb.append("window.__isProxyPage=true;");
            sb.append("</script>");
            sb.append("</body></html>");

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "text/html; charset=utf-8");
            headers.set("Access-Control-Allow-Origin", "*");
            return new ResponseEntity<>(sb.toString(), headers, HttpStatus.OK);
        } catch (Exception ex) {
            return ResponseEntity.ok("<html><body><h3>搜索失败: " + escapeHtml(ex.getMessage()) + "</h3></body></html>");
        }
    }

    private String formatCount(long count) {
        if (count >= 100000000) return String.format("%.1f亿", count / 100000000.0);
        if (count >= 10000) return String.format("%.1f万", count / 10000.0);
        return String.valueOf(count);
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
    public ResponseEntity<byte[]> proxy(@RequestParam String url, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        if ("OPTIONS".equals(servletRequest.getMethod())) {
            HttpHeaders corsHeaders = new HttpHeaders();
            corsHeaders.set("Access-Control-Allow-Origin", "*");
            corsHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            corsHeaders.set("Access-Control-Allow-Headers", "*");
            corsHeaders.set("Access-Control-Max-Age", "86400");
            corsHeaders.set("Access-Control-Allow-Credentials", "true");
            return new ResponseEntity<>(corsHeaders, HttpStatus.OK);
        }

        if (isBilibiliLogUrl(url)) {
            log.info("Blocking Bilibili log URL: {}", url);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Access-Control-Allow-Origin", "*");
            return new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
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

            if (isBilibiliSearchUrl(url)) {
                return handleBilibiliSearchProxy(url, servletRequest);
            }

            String method = servletRequest.getMethod();
            String proxyBaseUrl = resolveProxyBaseUrl(servletRequest);

            boolean isDouyin = isDouyinUrl(url);
            boolean isDoubao = isDoubaoUrl(url);
            boolean isQwen = isQwenUrl(url);
            boolean isYuanbao = isYuanbaoUrl(url);
            boolean isAiSite = isDoubao || isQwen || isYuanbao;
            boolean isBilibili = isBilibiliUrl(url);
            boolean isBing = isBingUrl(url);
            if (isAiSite) {
                log.info("AI site proxy request: {} {} (doubao={}, qwen={}, yuanbao={})", method, url, isDoubao, isQwen, isYuanbao);
            }
            String userAgent = isDouyin
                    ? "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
                    : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            String referer;
            if (isBilibili) {
                referer = "https://www.bilibili.com/";
            } else if (isBing) {
                referer = "https://cn.bing.com/";
            } else if (isDoubao) {
                referer = "https://www.doubao.com/";
            } else if (isQwen) {
                referer = "https://tongyi.aliyun.com/";
            } else if (isYuanbao) {
                referer = "https://yuanbao.tencent.com/";
            } else {
                referer = extractReferer(url);
            }

            boolean isVideoStream = isVideoStreamUrl(url);
            int timeoutSeconds = isVideoStream ? 120 : 60;
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Accept-Encoding", "identity")
                    .header("Referer", referer);

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                String origin;
                if (isBilibili) {
                    origin = "https://www.bilibili.com";
                } else if (isBing) {
                    origin = "https://cn.bing.com";
                } else if (isDoubao) {
                    origin = "https://www.doubao.com";
                } else if (isQwen) {
                    origin = "https://tongyi.aliyun.com";
                } else if (isYuanbao) {
                    origin = "https://yuanbao.tencent.com";
                } else {
                    origin = uri.getScheme() + "://" + uri.getAuthority();
                }
                reqBuilder.header("Origin", origin);
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

            if (isAiSite) {
                java.util.List<String> forwardPrefixes = java.util.List.of(
                        "x-xsrf-token", "x-csrf-token", "x-csrf", "x-requested-with",
                        "x-acw-ts", "x-acw-sign", "x-sgext", "x-sign", "x-token",
                        "x-tc-traceid", "x-tc-action", "x-tc-version", "x-tc-requestid",
                        "x-acs-action", "x-acs-version", "x-acs-signature-nonce",
                        "x-acs-date", "x-acs-accesskey-id", "x-acs-security-token",
                        "authorization", "cookie"
                );
                java.util.Enumeration<String> headerNames = servletRequest.getHeaderNames();
                if (headerNames != null) {
                    while (headerNames.hasMoreElements()) {
                        String hName = headerNames.nextElement();
                        String hLower = hName.toLowerCase();
                        for (String prefix : forwardPrefixes) {
                            if (hLower.equals(prefix) || hLower.startsWith(prefix + "-")) {
                                java.util.Enumeration<String> values = servletRequest.getHeaders(hName);
                                while (values != null && values.hasMoreElements()) {
                                    String val = values.nextElement();
                                    if ((hLower.equals("authorization") && authorization != null && !authorization.isEmpty())) {
                                        continue;
                                    }
                                    reqBuilder.header(hName, val);
                                }
                                break;
                            }
                        }
                    }
                }
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

            byte[] requestBody = null;
            String requestContentType = servletRequest.getContentType();
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                requestBody = servletRequest.getInputStream().readAllBytes();
                if (requestContentType != null && !requestContentType.isEmpty()) {
                    reqBuilder.header("Content-Type", requestContentType);
                }
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody != null ? requestBody : new byte[0]));
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            String acceptHeader = servletRequest.getHeader("Accept");
            boolean isSseRequest = acceptHeader != null && acceptHeader.contains("text/event-stream");
            boolean isStreamingBody = false;
            if (requestBody != null && requestBody.length > 0) {
                String requestBodyStr = new String(requestBody, StandardCharsets.UTF_8);
                if (requestBodyStr.contains("\"stream\"") && (requestBodyStr.contains("\"stream\":true") || requestBodyStr.contains("\"stream\": true"))) {
                    isStreamingBody = true;
                }
            }
            boolean isAiChatApi = isAiSite && "POST".equals(method) && isAiChatApiUrl(url);
            if (isAiChatApi) {
                log.info("AI chat API detected, enabling SSE streaming: {} {}", method, url);
            }

            if (isSseRequest || isStreamingBody || isAiChatApi) {
                reqBuilder.timeout(Duration.ofMinutes(10));
                HttpRequest httpRequest = reqBuilder.build();
                HttpResponse<java.io.InputStream> sseResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

                int statusCode = sseResponse.statusCode();
                String respContentType = sseResponse.headers().firstValue("Content-Type").orElse("");

                if (respContentType.contains("text/event-stream") || isSseRequest || isAiChatApi) {
                    List<String> setCookies = sseResponse.headers().allValues("Set-Cookie");
                    if (!setCookies.isEmpty()) {
                        storeCookies(uri, setCookies);
                    }

                    servletResponse.setStatus(statusCode);
                    servletResponse.setContentType(respContentType.isEmpty() ? "text/event-stream" : respContentType);
                    servletResponse.setCharacterEncoding("UTF-8");
                    servletResponse.addHeader("Access-Control-Allow-Origin", "*");
                    servletResponse.addHeader("Access-Control-Allow-Credentials", "true");
                    servletResponse.addHeader("Access-Control-Expose-Headers", "Content-Length");
                    servletResponse.addHeader("Cache-Control", "no-cache");
                    servletResponse.addHeader("Connection", "keep-alive");
                    sseResponse.headers().allValues("X-Accel-Buffering").forEach(v -> servletResponse.addHeader("X-Accel-Buffering", v));

                    try (java.io.InputStream is = sseResponse.body();
                         java.io.OutputStream os = servletResponse.getOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                            os.flush();
                        }
                    } catch (Exception e) {
                        log.info("SSE stream ended for: {}", url);
                    }
                    return null;
                } else {
                    try (java.io.InputStream is = sseResponse.body();
                         var baos = new java.io.ByteArrayOutputStream()) {
                        is.transferTo(baos);
                        var body = baos.toByteArray();
                        List<String> setCookies = sseResponse.headers().allValues("Set-Cookie");
                        if (!setCookies.isEmpty()) {
                            storeCookies(uri, setCookies);
                        }
                        HttpHeaders headers = new HttpHeaders();
                        headers.set("Content-Type", respContentType);
                        headers.set("Access-Control-Allow-Origin", "*");
                        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
                        headers.set("Access-Control-Allow-Headers", "*");
                        headers.set("Access-Control-Allow-Credentials", "true");
                        return new ResponseEntity<>(body, headers, HttpStatus.valueOf(statusCode));
                    }
                }
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
                    String proxyLocation = buildProxyUrl(resolvedLocation, proxyBaseUrl);
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
            boolean bodyModified = false;

            if (respContentType.contains("text/html")) {
                String html = new String(body, resolveCharset(respContentType, body));
                if (isAiSite) {
                    log.info("AI site HTML response: status={}, bodyLength={}, hasBody={}, url={}", statusCode, body.length, !html.trim().isEmpty(), url);
                }
                if (isBilibiliVideoUrl(url)) {
                    html = injectBilibiliVideoPlayer(html, url, proxyBaseUrl);
                    body = html.getBytes(StandardCharsets.UTF_8);
                    bodyModified = true;
                } else {
                    if (isBilibiliSearchUrl(url)) {
                        html = stripPageScripts(html);
                    }
                    html = rewriteHtmlResourceAttrs(html, url, proxyBaseUrl);
                    html = modifyHtml(html, url, proxyBaseUrl);
                    body = html.getBytes(StandardCharsets.UTF_8);
                    bodyModified = true;
                }
            } else if (respContentType.contains("text/css")) {
                String css = new String(body, resolveCharset(respContentType, body));
                css = rewriteCssUrls(css, url, proxyBaseUrl);
                body = css.getBytes(StandardCharsets.UTF_8);
                bodyModified = true;
            } else if (shouldRewriteTextBody(respContentType, isBilibili, isBing, isDoubao, isQwen, isYuanbao)) {
                String text = new String(body, resolveCharset(respContentType, body));
                text = rewriteTextBodyUrls(text, url, proxyBaseUrl);
                body = text.getBytes(StandardCharsets.UTF_8);
                bodyModified = true;
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", respContentType);
            if (respContentType.contains("text/html")) {
                headers.set("Content-Security-Policy", "frame-ancestors *");
            }
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "*");
            headers.set("Access-Control-Allow-Credentials", "true");
            headers.set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, ETag, Last-Modified");

            boolean isHtml = respContentType.contains("text/html");
            if (!isHtml) {
                response.headers().allValues("Content-Range").forEach(v -> headers.add("Content-Range", v));
                response.headers().allValues("Accept-Ranges").forEach(v -> headers.add("Accept-Ranges", v));
                if (contentEncoding.isEmpty() && !bodyModified) {
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

    private String resolveProxyBaseUrl(HttpServletRequest request) {
        String proto = firstHeaderValue(request.getHeader("X-Forwarded-Proto"));
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = firstHeaderValue(request.getHeader("X-Forwarded-Host"));
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            int port = request.getServerPort();
            boolean defaultPort = ("http".equalsIgnoreCase(proto) && port == 80)
                    || ("https".equalsIgnoreCase(proto) && port == 443);
            host = request.getServerName() + (defaultPort ? "" : ":" + port);
        }
        return proto + "://" + host;
    }

    private String firstHeaderValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int comma = value.indexOf(',');
        return comma >= 0 ? value.substring(0, comma).trim() : value.trim();
    }

    private String buildProxyUrl(String targetUrl, String proxyBaseUrl) {
        if (isVideoStreamUrl(targetUrl)) {
            return "/api/browser/stream?url=" + encodeURIComponent(targetUrl);
        }
        return "/api/browser/proxy?url=" + encodeURIComponent(targetUrl);
    }

    private String extractReferer(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority() + u.getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private String extractOrigin(String url) {
        try {
            URI u = URI.create(url);
            return u.getScheme() + "://" + u.getAuthority();
        } catch (Exception e) {
            return "";
        }
    }

    private volatile long lastCookieRefreshTime = 0;
    private static final long COOKIE_REFRESH_INTERVAL_MS = 30 * 60 * 1000L;

    private void ensureBilibiliCookies() {
        String apiCookies = getCookiesForUri(URI.create("https://api.bilibili.com"));
        String wwwCookies = getCookiesForUri(URI.create("https://www.bilibili.com"));
        boolean hasBuvid = (apiCookies.contains("buvid") || wwwCookies.contains("buvid"));
        long now = System.currentTimeMillis();
        boolean needRefresh = !hasBuvid || (now - lastCookieRefreshTime) > COOKIE_REFRESH_INTERVAL_MS;
        if (!needRefresh) return;
        try {
            log.info("Refreshing bilibili cookies, hasBuvid={}, lastRefresh={}s ago",
                    hasBuvid, hasBuvid ? (now - lastCookieRefreshTime) / 1000 : "never");
            HttpRequest navReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.bilibili.com/x/web-interface/nav"))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> navResp = httpClient.send(navReq, HttpResponse.BodyHandlers.ofString());
            List<String> setCookies = navResp.headers().allValues("Set-Cookie");
            if (!setCookies.isEmpty()) {
                storeCookies(URI.create("https://api.bilibili.com"), setCookies);
            }
            HttpRequest homeReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.bilibili.com/"))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> homeResp = httpClient.send(homeReq, HttpResponse.BodyHandlers.ofString());
            List<String> homeSetCookies = homeResp.headers().allValues("Set-Cookie");
            if (!homeSetCookies.isEmpty()) {
                storeCookies(URI.create("https://www.bilibili.com"), homeSetCookies);
            }
            lastCookieRefreshTime = System.currentTimeMillis();
            log.info("Bilibili cookies refreshed successfully");
        } catch (Exception e) {
            log.warn("Failed to refresh bilibili cookies: {}", e.getMessage());
        }
    }

    private ResponseEntity<byte[]> handleBilibiliSearchProxy(String url, HttpServletRequest servletRequest) {
        try {
            String keyword = "";
            int page = 1;
            int pageSize = 25;
            try {
                URI searchUri = URI.create(url);
                String query = searchUri.getQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] kv = param.split("=", 2);
                        if (kv.length == 2) {
                            String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                            String val = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                            if ("keyword".equalsIgnoreCase(key) || "q".equalsIgnoreCase(key)) {
                                keyword = val;
                            } else if ("page".equalsIgnoreCase(key)) {
                                try { page = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
                            } else if ("page_size".equalsIgnoreCase(key) || "pageSize".equalsIgnoreCase(key)) {
                                try { pageSize = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse bilibili search URL: {}", url);
            }

            if (keyword.isEmpty()) {
                String html = buildBilibiliSearchErrorHtml("请输入搜索关键词");
                return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                        .header("Access-Control-Allow-Origin", "*")
                        .body(html.getBytes(StandardCharsets.UTF_8));
            }

            ensureBilibiliCookies();

            String apiUrl = "https://api.bilibili.com/x/web-interface/search/type?keyword="
                    + encodeURIComponent(keyword)
                    + "&page=" + page
                    + "&page_size=" + pageSize
                    + "&search_type=video";

            String json = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    if (attempt > 0) {
                        Thread.sleep(800L * attempt);
                        lastCookieRefreshTime = 0;
                        ensureBilibiliCookies();
                    }
                    HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://search.bilibili.com/")
                            .header("Accept", "application/json,*/*")
                            .header("Origin", "https://search.bilibili.com")
                            .timeout(Duration.ofSeconds(15))
                            .GET();
                    String bilibiliCookies = getCookiesForUri(URI.create("https://api.bilibili.com"));
                    if (bilibiliCookies.isEmpty()) {
                        bilibiliCookies = getCookiesForUri(URI.create("https://www.bilibili.com"));
                    }
                    if (!bilibiliCookies.isEmpty()) {
                        reqBuilder.header("Cookie", bilibiliCookies);
                    }
                    HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
                    String body = response.body();
                    List<String> setCookies = response.headers().allValues("Set-Cookie");
                    if (!setCookies.isEmpty()) {
                        storeCookies(URI.create("https://api.bilibili.com"), setCookies);
                    }
                    if (body == null || body.trim().isEmpty() || body.trim().charAt(0) == '<') {
                        log.warn("Bilibili search API returned non-JSON on attempt {}: status={}", attempt + 1, response.statusCode());
                        continue;
                    }
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
                    int code = root.has("code") ? root.get("code").asInt() : 0;
                    if (code != 0) {
                        String message = root.has("message") ? root.get("message").asText() : "";
                        log.warn("Bilibili search API error on attempt {}: code={}, message={}", attempt + 1, code, message);
                        if (message.contains("banned") || message.contains("限制") || code == -352) {
                            lastCookieRefreshTime = 0;
                            continue;
                        }
                        String html = buildBilibiliSearchErrorHtml("B站搜索: " + escapeHtml(message));
                        return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                                .header("Access-Control-Allow-Origin", "*")
                                .body(html.getBytes(StandardCharsets.UTF_8));
                    }
                    json = body;
                    break;
                } catch (Exception e) {
                    log.warn("Bilibili search API attempt {} failed: {}", attempt + 1, e.getMessage());
                }
            }

            if (json == null) {
                String html = buildBilibiliSearchErrorHtml("B站搜索暂时不可用，请稍后重试");
                return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                        .header("Access-Control-Allow-Origin", "*")
                        .body(html.getBytes(StandardCharsets.UTF_8));
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode data = root.get("data");
            com.fasterxml.jackson.databind.JsonNode resultArr = (data != null && data.has("result")) ? data.get("result") : null;
            int numResults = (data != null && data.has("numResults")) ? data.get("numResults").asInt() : 0;
            int totalPages = (int) Math.ceil((double) numResults / pageSize);
            if (totalPages < 1) totalPages = 1;

            String proxyBaseUrl = resolveProxyBaseUrl(servletRequest);
            String html = buildBilibiliSearchResultsHtml(keyword, page, pageSize, resultArr, numResults, totalPages, proxyBaseUrl);
            return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .header("Content-Security-Policy", "frame-ancestors *")
                    .body(html.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            String html = buildBilibiliSearchErrorHtml("搜索失败: " + (ex.getMessage() != null ? ex.getMessage() : "未知错误"));
            return ResponseEntity.ok().header("Content-Type", "text/html; charset=utf-8")
                    .header("Access-Control-Allow-Origin", "*")
                    .body(html.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String buildBilibiliSearchResultsHtml(String keyword, int page, int pageSize,
                                                   com.fasterxml.jackson.databind.JsonNode resultArr,
                                                   int numResults, int totalPages, String proxyBaseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        sb.append("<title>搜索: ").append(escapeHtml(keyword)).append(" - 哔哩哔哩</title>");
        sb.append("<style>");
        sb.append(":root{--brand_pink:#FF6699;--brand_blue:#00AEEC;--brand_blue_thin:#DFF6FD;--stress_red:#F85A54;--success_green:#2AC864;--bg1:#FFFFFF;--bg2:#F6F7F8;--bg3:#F1F2F3;--text_white:#FFFFFF;--text1:#18191C;--text2:#61666D;--text3:#9499A0;--text4:#C9CCD0;--text_notice:#FA9600;--line_light:#F1F2F3;--line_regular:#E3E5E7;--line_bold:#C9CCD0;--graph_bg_regular:#F1F2F3;--graph_bg_thick:#E3E5E7;--graph_medium:#9499A0;--graph_icon:#61666D;--b_gutter:16px;--b_gutter_sm:12px;--b_gutter_md:28px;--b_radius:6px;--b_radius_sm:4px;--b_radius_md:8px}");
        sb.append("*{margin:0;padding:0;box-sizing:border-box}");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,Helvetica Neue,Helvetica,Arial,PingFang SC,Hiragino Sans GB,Microsoft YaHei,sans-serif;background:var(--bg2);color:var(--text1);font-size:14px;line-height:1.6;min-width:1100px}");
        sb.append("a{text-decoration:none;color:inherit;cursor:pointer}");
        sb.append(".b_container{width:1100px;margin:0 auto}");
        sb.append("@media(min-width:1440px){.b_container{width:1440px}}");
        sb.append("@media(min-width:1700px){.b_container{width:1700px}}");
        sb.append("@media(min-width:1920px){.b_container{width:1920px}}");
        sb.append("@media(min-width:2200px){.b_container{width:2200px}}");
        sb.append(".row{display:flex;flex-wrap:wrap;margin-left:calc(var(--b_gutter) * .5 * -1);margin-right:calc(var(--b_gutter) * .5 * -1)}");
        sb.append(".row>.col_1_5{position:relative;width:100%;padding-right:calc(var(--b_gutter) * .5);padding-left:calc(var(--b_gutter) * .5);flex:0 0 20%;max-width:20%}");
        sb.append(".search-header{background:var(--bg1);position:sticky;top:0;z-index:100;box-shadow:0 1px 3px rgba(0,0,0,.05)}");
        sb.append(".search-nav{display:flex;align-items:center;height:64px;padding:0 16px}");
        sb.append(".logo{display:flex;align-items:center;margin-right:24px;flex-shrink:0;cursor:pointer}");
        sb.append(".logo-text{font-size:24px;font-weight:700;color:var(--brand_pink);letter-spacing:-1px}");
        sb.append(".search-box{flex:1;max-width:520px;display:flex;align-items:center;height:40px;border-radius:8px;overflow:hidden;border:2px solid var(--line_regular);background:var(--bg1);transition:all .2s}");
        sb.append(".search-box:focus-within{border-color:var(--brand_blue);box-shadow:0 0 0 2px rgba(0,174,236,.12)}");
        sb.append(".search-box input{flex:1;height:100%;border:none;outline:none;padding:0 16px;font-size:14px;background:transparent;color:var(--text1)}");
        sb.append(".search-box input::placeholder{color:var(--text4)}");
        sb.append(".search-btn{width:56px;height:100%;display:flex;align-items:center;justify-content:center;background:var(--brand_blue);border:none;cursor:pointer;flex-shrink:0;transition:background .2s}");
        sb.append(".search-btn:hover{background:#00b5e5}");
        sb.append(".search-btn svg{width:18px;height:18px;fill:#fff}");
        sb.append(".filter-bar{padding:0 16px;display:flex;align-items:center;gap:0;height:46px;border-bottom:1px solid var(--line_light)}");
        sb.append(".filter-tab{padding:8px 20px;font-size:14px;color:var(--text2);cursor:pointer;transition:all .2s;white-space:nowrap;position:relative}");
        sb.append(".filter-tab:hover{color:var(--brand_blue)}");
        sb.append(".filter-tab.active{color:var(--brand_blue);font-weight:600}");
        sb.append(".filter-tab.active::after{content:'';position:absolute;bottom:0;left:20px;right:20px;height:3px;background:var(--brand_blue);border-radius:2px}");
        sb.append(".result-info{padding:16px 0 8px;font-size:13px;color:var(--text3)}");
        sb.append(".result-info b{color:var(--text1)}");
        sb.append(".video-list{padding:4px 0 20px}");
        sb.append(".bili-video-card{position:relative;margin-bottom:16px;--title-font-size:14px;--title-line-height:20px;--subtitle-font-size:12px;--subtitle-line-height:16px;--info-margin-top:6px;--icon-size:14px;--title-padding-right:22px}");
        sb.append(".bili-video-card .bili-video-card__image{position:relative;border-radius:6px;z-index:1;cursor:pointer}");
        sb.append(".bili-video-card .bili-video-card__image:hover .bili-video-card__mask{opacity:0;visibility:hidden}");
        sb.append(".bili-video-card .bili-video-card__image--wrap{padding-top:56.25%;background-color:var(--graph_bg_regular);border-radius:inherit;position:relative;overflow:hidden}");
        sb.append(".bili-video-card .bili-video-card__cover{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;z-index:1;border-radius:6px;overflow:hidden;transition:transform .3s}");
        sb.append(".bili-video-card:hover .bili-video-card__cover{transform:scale(1.05)}");
        sb.append(".bili-video-card .bili-video-card__mask{position:absolute;top:0;left:0;width:100%;height:100%;z-index:2;opacity:1;transition:all .2s linear .2s;pointer-events:none}");
        sb.append(".bili-video-card .bili-video-card__stats{display:flex;align-items:center;justify-content:space-between;position:absolute;left:0;bottom:0;padding:16px 8px 6px;width:100%;height:38px;font-size:var(--subtitle-font-size);line-height:var(--icon-size);color:#fff;background-image:linear-gradient(180deg,rgba(0,0,0,0),rgba(0,0,0,.5));z-index:2;box-sizing:border-box;border-bottom-left-radius:6px;border-bottom-right-radius:6px;word-break:keep-all}");
        sb.append(".bili-video-card .bili-video-card__stats--left{flex:1;display:flex;align-items:center}");
        sb.append(".bili-video-card .bili-video-card__stats--left .bili-video-card__stats--item{display:inline-flex;align-items:center;margin-right:12px}");
        sb.append(".bili-video-card .bili-video-card__stats--left .bili-video-card__stats--icon{width:var(--icon-size);height:var(--icon-size);margin-right:2px}");
        sb.append(".bili-video-card .bili-video-card__stats--right{display:flex;align-items:center}");
        sb.append(".bili-video-card .bili-video-card__info{margin-top:var(--info-margin-top)}");
        sb.append(".bili-video-card .bili-video-card__info--right{flex:1;position:relative}");
        sb.append(".bili-video-card .bili-video-card__info--tit{display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;text-overflow:ellipsis;word-break:break-word!important;word-break:break-all;line-break:anywhere;-webkit-line-clamp:2;padding-right:var(--title-padding-right);font-size:var(--title-font-size);line-height:var(--title-line-height);height:calc(2 * var(--title-line-height));color:var(--text1);font-weight:500;transition:color .2s linear;cursor:pointer}");
        sb.append(".bili-video-card .bili-video-card__info--tit:hover{color:var(--brand_blue)}");
        sb.append(".bili-video-card .bili-video-card__info--tit em{color:var(--brand_pink);font-style:normal;font-weight:700}");
        sb.append(".bili-video-card .bili-video-card__info--bottom{display:flex;align-items:center;margin-top:2px;font-size:var(--subtitle-font-size);line-height:var(--subtitle-line-height);color:var(--text3)}");
        sb.append(".bili-video-card .bili-video-card__info--owner{display:inline-flex;align-items:center;transition:color .2s linear;max-width:100%;cursor:pointer}");
        sb.append(".bili-video-card .bili-video-card__info--owner:hover{color:var(--brand_blue)}");
        sb.append(".bili-video-card .bili-video-card__info--author{flex:1;display:-webkit-box;overflow:hidden;-webkit-box-orient:vertical;text-overflow:ellipsis;word-break:break-word!important;word-break:break-all;line-break:anywhere;-webkit-line-clamp:1}");
        sb.append(".bili-video-card .bili-video-card__info--date{margin-left:4px;flex-shrink:0;white-space:nowrap}");
        sb.append(".bili-video-card .bili-video-card__info--icon{width:var(--subtitle-line-height);height:var(--subtitle-line-height);margin-right:4px;flex-shrink:0;border-radius:50%}");
        sb.append(".empty{text-align:center;padding:80px 20px;color:var(--text3);font-size:15px}");
        sb.append(".empty-icon{width:80px;height:80px;margin:0 auto 16px}");
        sb.append("</style></head><body>");

        sb.append("<div class='search-header'>");
        sb.append("<div class='search-nav'>");
        sb.append("<div class='logo' onclick=\"window.parent.postMessage({type:'browser_navigate',url:'https://www.bilibili.com'},'*')\">");
        sb.append("<span class='logo-text'>哔哩哔哩</span>");
        sb.append("</div>");
        sb.append("<div class='search-box'>");
        sb.append("<input type='text' id='searchInput' value='").append(escapeHtml(keyword)).append("' placeholder='搜索B站视频' onkeydown='if(event.key===\"Enter\")doSearch()'>");
        sb.append("<button class='search-btn' onclick='doSearch()'>");
        sb.append("<svg viewBox='0 0 24 24'><path d='M15.5 14h-.79l-.28-.27A6.47 6.47 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z'/></svg>");
        sb.append("</button>");
        sb.append("</div>");
        sb.append("</div>");

        sb.append("<div class='filter-bar'>");
        sb.append("<span class='filter-tab'>综合</span>");
        sb.append("<span class='filter-tab active'>视频</span>");
        sb.append("<span class='filter-tab'>番剧</span>");
        sb.append("<span class='filter-tab'>影视</span>");
        sb.append("<span class='filter-tab'>直播</span>");
        sb.append("<span class='filter-tab'>专栏</span>");
        sb.append("<span class='filter-tab'>话题</span>");
        sb.append("<span class='filter-tab'>用户</span>");
        sb.append("</div>");
        sb.append("</div>");

        sb.append("<div class='b_container'>");
        sb.append("<div class='result-info'>");
        sb.append("共 <b>").append(numResults).append("</b> 条结果");
        sb.append("</div>");

        sb.append("<div class='video-list'>");
        sb.append("<div class='row'>");

        if (resultArr != null && resultArr.isArray() && resultArr.size() > 0) {
            for (com.fasterxml.jackson.databind.JsonNode item : resultArr) {
                String title = item.has("title") ? item.get("title").asText() : "";
                String author = item.has("author") ? item.get("author").asText() : "";
                String pic = item.has("pic") ? item.get("pic").asText() : "";
                String bvid = item.has("bvid") ? item.get("bvid").asText() : "";
                String arcurl = item.has("arcurl") ? item.get("arcurl").asText() : "";
                long play = item.has("play") ? item.get("play").asLong() : 0;
                long danmaku = item.has("video_review") ? item.get("video_review").asLong() : 0;
                String duration = item.has("duration") ? item.get("duration").asText() : "";
                String pubdate = "";
                if (item.has("pubdate") && item.get("pubdate").isNumber()) {
                    long ts = item.get("pubdate").asLong();
                    java.time.LocalDateTime dt = java.time.LocalDateTime.ofEpochSecond(ts, 0, java.time.ZoneOffset.ofHours(8));
                    pubdate = dt.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd"));
                }

                title = title.replaceAll("<em class=\"keyword\">", "<em>").replaceAll("</em>", "</em>");

                if (!pic.startsWith("http")) {
                    pic = "https:" + pic;
                }
                String proxyPic = buildProxyUrl(pic, proxyBaseUrl);
                String videoUrl = arcurl.isEmpty() ? "https://www.bilibili.com/video/" + bvid : arcurl;

                sb.append("<div class='col_1_5'>");
                sb.append("<div class='bili-video-card'>");
                sb.append("<div class='bili-video-card__image' onclick=\"window.parent.postMessage({type:'browser_navigate',url:'")
                  .append(escapeJs(videoUrl)).append("'},'*')\">");
                sb.append("<div class='bili-video-card__image--wrap'>");
                sb.append("<img class='bili-video-card__cover' src='").append(proxyPic).append("' alt='' loading='lazy'>");
                sb.append("<div class='bili-video-card__mask'></div>");
                sb.append("<div class='bili-video-card__stats'>");
                sb.append("<div class='bili-video-card__stats--left'>");
                sb.append("<span class='bili-video-card__stats--item'><svg class='bili-video-card__stats--icon' viewBox='0 0 24 24'><path d='M8 5v14l11-7z' fill='#fff'/></svg>").append(formatCount(play)).append("</span>");
                sb.append("<span class='bili-video-card__stats--item'><svg class='bili-video-card__stats--icon' viewBox='0 0 24 24'><path d='M20 2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h14l4 4V4c0-1.1-.9-2-2-2zm-2 12H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z' fill='#fff'/></svg>").append(formatCount(danmaku)).append("</span>");
                sb.append("</div>");
                if (!duration.isEmpty()) {
                    sb.append("<div class='bili-video-card__stats--right'>").append(escapeHtml(duration)).append("</div>");
                }
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("<div class='bili-video-card__info'>");
                sb.append("<div class='bili-video-card__info--right'>");
                sb.append("<div class='bili-video-card__info--tit' onclick=\"window.parent.postMessage({type:'browser_navigate',url:'")
                  .append(escapeJs(videoUrl)).append("'},'*')\">").append(title).append("</div>");
                sb.append("<div class='bili-video-card__info--bottom'>");
                sb.append("<span class='bili-video-card__info--owner' onclick=\"window.parent.postMessage({type:'browser_navigate',url:'https://space.bilibili.com/'},'*')\">");
                sb.append("<svg class='bili-video-card__info--icon' viewBox='0 0 24 24'><path d='M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z' fill='var(--text3)'/></svg>");
                sb.append("<span class='bili-video-card__info--author'>").append(escapeHtml(author)).append("</span>");
                if (!pubdate.isEmpty()) {
                    sb.append("<span class='bili-video-card__info--date'>").append(pubdate).append("</span>");
                }
                sb.append("</span>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</div>");
                sb.append("</div>");
            }
        } else {
            sb.append("<div class='empty' style='grid-column:1/-1'>");
            sb.append("<div class='empty-icon'><svg viewBox='0 0 24 24' width='80' height='80'><path d='M15.5 14h-.79l-.28-.27A6.47 6.47 0 0016 9.5 6.5 6.5 0 109.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z' fill='#e3e5e7'/></svg></div>");
            sb.append("没有找到相关视频");
            sb.append("</div>");
        }

        sb.append("</div>");
        sb.append("</div>");
        sb.append("</div>");

        sb.append("<script>");
        sb.append("function doSearch(){var kw=document.getElementById('searchInput').value.trim();if(kw){window.parent.postMessage({type:'browser_navigate',url:'https://search.bilibili.com/all?keyword='+encodeURIComponent(kw)},'*');}}");
        sb.append("window.__isProxyPage=true;");
        sb.append("</script>");
        sb.append("</body></html>");

        return sb.toString();
    }

    private String buildBilibiliSearchErrorHtml(String message) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{display:flex;justify-content:center;align-items:center;height:100vh;font-family:system-ui;color:#666;flex-direction:column;background:#f4f5f7}"
                + "h3{color:#333;margin-bottom:8px}p{margin:4px 0}</style></head>"
                + "<body><h3>" + escapeHtml(message) + "</h3>"
                + "<p style='margin-top:16px;font-size:13px;color:#999'>请尝试刷新或返回主页</p></body></html>";
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

    private boolean isBilibiliVideoUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && host.endsWith(".bilibili.com") && path != null
                    && java.util.regex.Pattern.compile("/video/(BV[a-zA-Z0-9]+|av\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(path).find();
        } catch (Exception e) {
            return false;
        }
    }

    private String injectBilibiliVideoPlayer(String html, String url, String proxyBaseUrl) {
        String playPageUrl = "/api/browser/bilibili-play-page?url=" + encodeURIComponent(url);
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<title>Bilibili Player</title>"
                + "<style>*{margin:0;padding:0}html,body,iframe{width:100%;height:100%;border:none;overflow:hidden}</style>"
                + "</head><body>"
                + "<iframe src='" + escapeJs(playPageUrl) + "' allow='autoplay;fullscreen;encrypted-media;picture-in-picture' allowfullscreen></iframe>"
                + "</body></html>";
    }

    private boolean isDoubaoUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("doubao.com")
                    || host.endsWith(".doubao.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isQwenUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("tongyi.aliyun.com")
                    || host.endsWith(".tongyi.aliyun.com")
                    || host.equalsIgnoreCase("qianwen.aliyun.com")
                    || host.endsWith(".qianwen.aliyun.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isYuanbaoUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("yuanbao.tencent.com")
                    || host.endsWith(".yuanbao.tencent.com")
                    || host.equalsIgnoreCase("hunyuan.tencent.com")
                    || host.endsWith(".hunyuan.tencent.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isAiSiteUrl(String url) {
        return isDoubaoUrl(url) || isQwenUrl(url) || isYuanbaoUrl(url);
    }

    private boolean isAiChatApiUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains("/api/chat") || lower.contains("/api/v1/chat")
                || lower.contains("/api/conversation") || lower.contains("/api/v1/conversation")
                || lower.contains("/completion") || lower.contains("/completions")
                || lower.contains("/api/generate") || lower.contains("/api/v1/generate")
                || lower.contains("/api/message") || lower.contains("/api/v1/message")
                || lower.contains("/api/stream") || lower.contains("/api/v1/stream");
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

    private boolean isBilibiliUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("bilibili.com")
                    || host.endsWith(".bilibili.com")
                    || host.endsWith(".hdslb.com")
                    || host.endsWith(".bilivideo.com")
                    || host.endsWith(".biliapi.net")
                    || host.endsWith(".bilivideo.cn")
                    || host.endsWith(".acgvideo.com"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBilibiliLogUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains("data.bilibili.com/log")
                || lower.contains("api.bilibili.com/x/report")
                || lower.contains("/log/web");
    }

    private boolean isBilibiliVideoCdnUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains("bilivideo.com") || lower.contains("acgvideo.com")
                || lower.contains("hdslb.com") || lower.contains("mountaintoys.cn")
                || lower.contains(".mcdn.") || lower.contains("/upgcxcode/");
    }

    private boolean isBingUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equalsIgnoreCase("bing.com")
                    || host.endsWith(".bing.com")
                    || host.endsWith(".bingapis.com")
                    || host.endsWith(".bing.net")
                    || host.endsWith(".microsoft.com")
                    || host.endsWith(".microsoft.net"));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isVideoStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m4s") || lower.contains(".flv")
                || lower.contains(".m3u8") || lower.contains(".ts")
                || lower.contains("bilivideo.com") || lower.contains("acgvideo.com")
                || lower.contains("/playurl");
    }

    private boolean shouldRewriteTextBody(String contentType, boolean isBilibili, boolean isBing, boolean isDoubao, boolean isQwen, boolean isYuanbao) {
        if (contentType == null) {
            return false;
        }
        String lower = contentType.toLowerCase();
        boolean isJs = lower.contains("javascript");
        if (!isJs) {
            return false;
        }
        return isBilibili || isBing || isDoubao || isQwen || isYuanbao;
    }

    private String rewriteTextBodyUrls(String text, String baseUrl, String proxyBaseUrl) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(https?://[^\\s\"'<>\\\\]+)"
        ).matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String raw = matcher.group(1);
            String candidate = trimTrailingUrlChars(raw);
            if (!shouldRewriteAbsoluteUrl(candidate)) {
                matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(raw));
                continue;
            }
            String resolved;
            try {
                resolved = new URL(new URL(baseUrl), candidate).toString();
            } catch (Exception e) {
                resolved = candidate;
            }
            String proxied = buildProxyUrl(resolved, proxyBaseUrl);
            String suffix = raw.substring(candidate.length());
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(proxied + suffix));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String trimTrailingUrlChars(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        int end = url.length();
        while (end > 0) {
            char ch = url.charAt(end - 1);
            if (ch == ')' || ch == ']' || ch == '}' || ch == ',' || ch == ';' || ch == '.') {
                end--;
                continue;
            }
            break;
        }
        return url.substring(0, end);
    }

    private boolean shouldRewriteAbsoluteUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        if (url.contains("/api/browser/")) {
            return false;
        }
        if (url.startsWith("data:") || url.startsWith("blob:") || url.startsWith("javascript:")) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    private String stripPageScripts(String html) {
        return html.replaceAll("(?is)<script\\b(?![^>]*type=[\"']application/ld\\+json[\"'])[^>]*>.*?</script>", "");
    }

    private String modifyHtml(String html, String originalUrl, String proxyBaseUrl) {
        String script = "<script>(function(){"
                + "var _base='" + escapeJs(originalUrl) + "';"
                + "window.__isProxyPage=true;"
                + "var _realTop=window.top;"
                + "try{Object.defineProperty(window,'parent',{get:function(){return window;}});}catch(e){}"
                + "try{Object.defineProperty(window,'top',{get:function(){return window;}});}catch(e){}"
                + "try{Object.defineProperty(window,'frameElement',{get:function(){return null;}});}catch(e){}"
                + "var _bo;"
                + "try{_bo=new URL(_base).origin;}catch(e){}"
                + "var _lo=window.location.origin;"
                + "function navigateTo(u){"
                + "if(!u||u.indexOf('javascript:')===0||u.indexOf('#')===0)return false;"
                + "var resolved;"
                + "try{resolved=new URL(u,_base).href;}catch(e){resolved=u;}"
                + "if(resolved.indexOf('/api/browser/')!==-1){try{resolved=new URL(resolved,window.location.href).searchParams.get('url')||resolved;}catch(e){}}"
                + "try{"
                + "var cur=new URL(_base).href;"
                + "if(resolved===cur||resolved===cur.replace(/\\/$/,'')||resolved.replace(/\\/$/,'')===cur)return false;"
                + "}catch(e){}"
                + "console.log('[Proxy navigateTo] u='+u+' resolved='+resolved+' _base='+_base);"
                + "try{_realTop.postMessage({type:'browser_navigate',url:resolved},'*');}catch(e){}"
                + "return true;"
                + "}"
                + "function proxyUrl(u){"
                + "if(u==null)return _lo+'/api/browser/empty';"
                + "if(typeof u!=='string')u=String(u);"
                + "if(!u||u==='undefined'||u==='null')return _lo+'/api/browser/empty';"
                + "if(u.indexOf('/api/browser/')!==-1)return u;"
                + "if(u.indexOf('data:')===0)return u;"
                + "if(u.indexOf('blob:')===0)return u;"
                + "if(u.indexOf('javascript:')===0)return u;"
                + "if(u.indexOf('about:')===0)return u;"
                + "var r;"
                + "try{r=new URL(u,_base).href;}catch(e){return u;}"
                + "if(r.indexOf(_lo)===0&&r.indexOf('/api/browser/')!==-1)return r;"
                + "if(_bo&&_lo&&r.indexOf(_lo)===0){"
                + "r=_bo+r.substring(_lo.length);"
                + "}"
                + "return _lo+'/api/browser/proxy?url='+encodeURIComponent(r);"
                + "}"
                + "try{"
                + "var _realLoc=window.location;"
                + "var _fakeLoc={"
                + "get href(){return _base;},set href(v){if(typeof v==='string'&&v.length>0){try{var resolved=new URL(v,_base).href;if(resolved.indexOf(_bo)===0){history.pushState(null,'',_lo+'/api/browser/proxy?url='+encodeURIComponent(resolved));return;}}catch(ex){}_realLoc.replace(proxyUrl(v));return;}_realLoc.replace(v);},"
                + "get origin(){return _bo;},"
                + "get hostname(){try{return new URL(_base).hostname;}catch(e){return '';}},"
                + "get protocol(){try{return new URL(_base).protocol;}catch(e){return 'https:';}},"
                + "get host(){try{return new URL(_base).host;}catch(e){return '';}},"
                + "get pathname(){try{return new URL(_base).pathname;}catch(e){return '/';}},"
                + "get search(){try{return new URL(_base).search;}catch(e){return '';}},"
                + "get hash(){try{return new URL(_base).hash;}catch(e){return '';}},"
                + "get port(){try{return new URL(_base).port;}catch(e){return '';}},"
                + "assign:function(u){_realLoc.assign(proxyUrl(u));},"
                + "replace:function(u){_realLoc.replace(proxyUrl(u));},"
                + "reload:function(){var now=Date.now();if(now-_lastReload<3000){console.log('[Proxy] blocked rapid reload');return;}_lastReload=now;_realLoc.reload();},"
                + "toString:function(){return _base;}"
                + "};"
                + "try{Object.defineProperty(window,'location',{get:function(){return _fakeLoc;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(document,'location',{get:function(){return _fakeLoc;},configurable:true});}catch(e){}"
                + "}catch(e){}"
                + "try{"
                + "var _locProps=['hostname','origin','protocol','host','pathname','search','hash','port','href'];"
                + "for(var _pi=0;_pi<_locProps.length;_pi++){"
                + "(function(prop){"
                + "var _desc=Object.getOwnPropertyDescriptor(Location.prototype,prop)||Object.getOwnPropertyDescriptor(HTMLHyperlinkElementUtils.prototype,prop);"
                + "if(_desc&&_desc.get){"
                + "var _origGetter=_desc.get;"
                + "Object.defineProperty(Location.prototype,prop,{"
                + "get:function(){"
                + "try{"
                + "var fakeVal=_fakeLoc[prop];"
                + "if(fakeVal!==undefined&&fakeVal!=='')return fakeVal;"
                + "}catch(e){}"
                + "return _origGetter.call(this);"
                + "},"
                + "set:prop==='href'&&_desc.set?function(v){console.log('[Proxy loc.href set] v='+v);if(typeof v==='string'&&v.length>0){try{var resolved=new URL(v,_base).href;if(resolved.indexOf(_bo)===0){history.pushState(null,'',_lo+'/api/browser/proxy?url='+encodeURIComponent(resolved));return;}}catch(ex){}var pv=proxyUrl(v);if(pv!==v){_desc.set.call(this,pv);return;}}_desc.set.call(this,v);}:_desc.set,"
                + "configurable:true"
                + "});"
                + "}"
                + "})(_locProps[_pi]);"
                + "}"
                + "var _locAssign=Location.prototype.assign;"
                + "Location.prototype.assign=function(u){"
                + "try{"
                + "var resolved=new URL(u,_base).href;"
                + "if(resolved.indexOf(_bo)===0){"
                + "var pU=_lo+'/api/browser/proxy?url='+encodeURIComponent(resolved);"
                + "history.pushState(null,'',pU);"
                + "return;"
                + "}"
                + "}catch(ex){}"
                + "_locAssign.call(this,proxyUrl(u));"
                + "};"
                + "var _locReplace=Location.prototype.replace;"
                + "Location.prototype.replace=function(u){"
                + "try{"
                + "var resolved=new URL(u,_base).href;"
                + "if(resolved.indexOf(_bo)===0){"
                + "var pU=_lo+'/api/browser/proxy?url='+encodeURIComponent(resolved);"
                + "history.replaceState(null,'',pU);"
                + "return;"
                + "}"
                + "}catch(ex){}"
                + "_locReplace.call(this,proxyUrl(u));"
                + "};"
                + "}catch(e){}"
                + "try{"
                + "var _pushState=History.prototype.pushState;"
                + "History.prototype.pushState=function(st,title,u){"
                + "if(typeof u==='string'&&u.length>0){"
                + "try{var resolved=new URL(u,_base).href;"
                + "if(resolved.indexOf(_bo)===0){"
                + "var pU=_lo+'/api/browser/proxy?url='+encodeURIComponent(resolved);"
                + "return _pushState.call(this,st,title,pU);"
                + "}"
                + "if(resolved.indexOf(_lo)!==0){"
                + "navigateTo(resolved);return;"
                + "}"
                + "}catch(ex){}"
                + "}"
                + "return _pushState.call(this,st,title,u);"
                + "};"
                + "var _replaceState=History.prototype.replaceState;"
                + "History.prototype.replaceState=function(st,title,u){"
                + "if(typeof u==='string'&&u.length>0){"
                + "try{var resolved=new URL(u,_base).href;"
                + "if(resolved.indexOf(_bo)===0){"
                + "var pU=_lo+'/api/browser/proxy?url='+encodeURIComponent(resolved);"
                + "return _replaceState.call(this,st,title,pU);"
                + "}"
                + "if(resolved.indexOf(_lo)!==0){"
                + "navigateTo(resolved);return;"
                + "}"
                + "}catch(ex){}"
                + "}"
                + "return _replaceState.call(this,st,title,u);"
                + "};"
                + "}catch(e){}"
                + "window.addEventListener('popstate',function(e){"
                + "try{var href=window.location.href;if(href.indexOf('/api/browser/')===-1){window.location.replace(proxyUrl(href));}}catch(ex){}"
                + "},false);"
                + "var _proxyWin={closed:false,location:window.location,document:document,close:function(){this.closed=true;},postMessage:function(m,o){try{window.postMessage(m,o);}catch(e){}},focus:function(){},blur:function(){},open:function(){return _proxyWin;}};"
                + "window.open=function(u,n,f){if(u){navigateTo(u);}return _proxyWin;};"
                + "var _fetch=window.fetch;"
                + "window.fetch=function(input,init){"
                + "var u;"
                + "if(typeof input==='string'){u=input;}"
                + "else if(input instanceof Request){u=input.url;}"
                + "else if(input&&input.href){u=input.href;}"
                + "else{u=String(input);}"
                + "var pu=proxyUrl(u);"
                + "if(pu!==u){"
                + "if(!init)init={};"
                + "if(!init.credentials)init.credentials='include';"
                + "if(typeof input==='string'){input=pu;}"
                + "else if(input instanceof Request){"
                + "var ni={};"
                + "for(var k in init)ni[k]=init[k];"
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
                + "var result=_xhrOpen.call(this,m,proxyUrl(u),a!==false,user,pass);"
                + "try{this.withCredentials=true;}catch(e){}"
                + "return result;"
                + "};"
                + "var _ES=window.EventSource;"
                + "if(_ES){"
                + "window.EventSource=function(u,opts){"
                + "if(!opts)opts={};"
                + "if(!opts.withCredentials)opts.withCredentials=true;"
                + "return new _ES(proxyUrl(u),opts);"
                + "};"
                + "window.EventSource.prototype=_ES.prototype;"
                + "}"
                + "var _WebSocket=window.WebSocket;"
                + "if(_WebSocket){"
                + "window.WebSocket=function(u,protocols){"
                + "var resolved;"
                + "try{resolved=new URL(u,_base).href;}catch(e){resolved=u;}"
                + "if(resolved.indexOf('ws://')===0||resolved.indexOf('wss://')===0){"
                + "if(resolved.indexOf('broadcast.chat.bilibili.com')!==-1||resolved.indexOf('chat.bilibili.com')!==-1){"
                + "console.log('Blocked Bilibili danmaku WebSocket:',resolved);"
                + "var fakeWs={readyState:3,CLOSED:3,OPEN:1,CONNECTING:0,CLOSING:2,send:function(){},close:function(){},onopen:null,onmessage:null,onclose:null,onerror:null};"
                + "setTimeout(function(){if(fakeWs.onclose)fakeWs.onclose({code:1000,reason:'Blocked by proxy'});},0);"
                + "return fakeWs;"
                + "}"
                + "var wsHost=resolved.replace(/^ws(s?):\\/\\//,'').split('/')[0].split(':')[0];"
                + "var loHost=_lo.replace(/^https?:\\/\\//,'').split(':')[0];"
                + "if(wsHost===loHost||wsHost==='localhost'||wsHost==='127.0.0.1'){"
                + "try{"
                + "var boUrl=new URL(_base);"
                + "var wsProto=boUrl.protocol==='https:'?'wss:':'ws:';"
                + "var wsPort=boUrl.port?(boUrl.protocol==='https:'?'':(':'+boUrl.port)):(boUrl.protocol==='https:'?'':':80');"
                + "var pathAndQuery=resolved.replace(/^ws(s?):\\/\\/[^\\/]+/,'');"
                + "resolved=wsProto+'//'+boUrl.hostname+(wsPort||'')+pathAndQuery;"
                + "console.log('[Proxy WS] Rewrote local WS URL to:',resolved);"
                + "}catch(ex){console.log('[Proxy WS] Failed to rewrite WS URL:',ex);}"
                + "}"
                + "var proxyWsUrl=_lo.replace(/^http/,'ws')+'/api/browser/ws-proxy?url='+encodeURIComponent(resolved);"
                + "console.log('[Proxy WS] Connecting to:',proxyWsUrl,'target:',resolved);"
                + "if(protocols){return new _WebSocket(proxyWsUrl,protocols);}"
                + "return new _WebSocket(proxyWsUrl);"
                + "}"
                + "if(protocols){return new _WebSocket(u,protocols);}"
                + "return new _WebSocket(u);"
                + "};"
                + "window.WebSocket.prototype=_WebSocket.prototype;"
                + "window.WebSocket.CONNECTING=_WebSocket.CONNECTING;"
                + "window.WebSocket.OPEN=_WebSocket.OPEN;"
                + "window.WebSocket.CLOSING=_WebSocket.CLOSING;"
                + "window.WebSocket.CLOSED=_WebSocket.CLOSED;"
                + "}"
                + "try{"
                + "var _sendBeacon=navigator.sendBeacon.bind(navigator);"
                + "navigator.sendBeacon=function(u,data){"
                + "try{"
                + "var resolved=new URL(u,_base).href;"
                + "var pu=proxyUrl(resolved);"
                + "if(pu!==resolved){"
                + "if(data){_fetch.call(window,pu,{method:'POST',body:data,credentials:'include'});}"
                + "else{_fetch.call(window,pu,{method:'POST',credentials:'include'});}"
                + "return true;"
                + "}"
                + "}catch(e){}"
                + "return _sendBeacon(u,data);"
                + "};"
                + "}catch(e){}"
                + "try{"
                + "var _formSubmit=HTMLFormElement.prototype.submit;"
                + "HTMLFormElement.prototype.submit=function(){"
                + "try{"
                + "var f=this;"
                + "var u=f.getAttribute('action')||_base;"
                + "if(u.indexOf('/api/browser/')!==-1){try{u=new URL(u,window.location.href).searchParams.get('url')||u;}catch(ex){}}"
                + "var resolved=new URL(u,_base).href;"
                + "var fd=new FormData(f);"
                + "var p=new URLSearchParams(fd).toString();"
                + "var sep=resolved.indexOf('?')===-1?'?':'&';"
                + "navigateTo(resolved+sep+p);"
                + "return;"
                + "}catch(e){}"
                + "_formSubmit.call(this);"
                + "};"
                + "}catch(e){}"
                + "try{"
                + "var _origImg=window.Image;"
                + "window.Image=function(w,h){"
                + "var img=arguments.length>=2?new _origImg(w,h):new _origImg();"
                + "var _origSrcSet=Object.getOwnPropertyDescriptor(HTMLImageElement.prototype,'src');"
                + "if(_origSrcSet&&_origSrcSet.set){"
                + "Object.defineProperty(img,'src',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origSrcSet.set.call(this,v);},"
                + "get:_origSrcSet.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "return img;"
                + "};"
                + "window.Image.prototype=_origImg.prototype;"
                + "}catch(e){}"
                + "document.addEventListener('click',function(e){"
                + "var n=e.target;"
                + "while(n&&n.tagName!=='A'){n=n.parentNode;}"
                + "if(n&&n.href){"
                + "var href=n.href;"
                + "console.log('[Proxy click] href='+href);"
                + "if(href.indexOf('javascript:')===0||href.indexOf('#')===0)return;"
                + "if(href.indexOf('/api/browser/')!==-1){try{href=new URL(href,window.location.href).searchParams.get('url')||href;}catch(e){}}"
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
                + "var u=f.getAttribute('action')||_base;"
                + "if(u.indexOf('/api/browser/')!==-1){try{u=new URL(u,window.location.href).searchParams.get('url')||u;}catch(e){u=decodeURIComponent(u.split('url=')[1]||u);}}"
                + "var sep=u.indexOf('?')===-1?'?':'&';"
                + "navigateTo(u+sep+p);"
                + "}"
                + "},true);"
                + "function proxyNodeAttrs(n){"
                + "if(!n||!n.tagName)return;"
                + "var tag=n.tagName.toUpperCase();"
                + "if(tag==='SCRIPT'||tag==='LINK'){"
                + "try{if(n.hasAttribute('crossorigin'))n.removeAttribute('crossorigin');}catch(e){}"
                + "try{if(n.hasAttribute('integrity'))n.removeAttribute('integrity');}catch(e){}"
                + "}"
                + "if(n.getAttribute){"
                + "var ds=n.getAttribute('data-src');"
                + "if(ds&&ds.indexOf('http')===0&&ds.indexOf('/api/browser/')===-1){n.setAttribute('data-src',proxyUrl(ds));}"
                + "}"
                + "if((tag==='IMG'||tag==='VIDEO'||tag==='AUDIO'||tag==='SOURCE')&&n.src){"
                + "var s=String(n.src);"
                + "if(s.indexOf('about:')===0||s.indexOf('javascript:')===0||s.indexOf('data:')===0||s.indexOf('blob:')===0||s.indexOf('/api/browser/')!==-1)return;"
                + "try{var absUrl=new URL(s,_base).href;n.src=proxyUrl(absUrl);}catch(e){}"
                + "}"
                + "}"
                + "var _createElement=document.createElement.bind(document);"
                + "document.createElement=function(tag){"
                + "var el=_createElement(tag);"
                + "if(typeof tag==='string'){"
                + "var tl=tag.toUpperCase();"
                + "if(tl==='SCRIPT'||tl==='LINK'){"
                + "var origSet=el.setAttribute?el.setAttribute.bind(el):null;"
                + "if(origSet){"
                + "el.setAttribute=function(name,val){"
                + "var nl=name.toLowerCase();"
                + "if(nl==='crossorigin'||nl==='integrity')return;"
                + "if((name==='src'||name==='href')&&typeof val==='string'&&val.length>0){val=proxyUrl(val);}"
                + "return origSet(name,val);"
                + "};"
                + "}"
                + "try{"
                + "var srcDesc=Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype,'src')||Object.getOwnPropertyDescriptor(HTMLElement.prototype,'src');"
                + "if(srcDesc&&srcDesc.set){"
                + "var origSrcSet=srcDesc.set;"
                + "Object.defineProperty(el,'src',{set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}origSrcSet.call(this,v);},get:srcDesc.get,configurable:true});"
                + "}"
                + "}catch(e){}"
                + "try{Object.defineProperty(el,'crossOrigin',{set:function(){},get:function(){return null;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(el,'integrity',{set:function(){},get:function(){return '';},configurable:true});}catch(e){}"
                + "}"
                + "}"
                + "return el;"
                + "};"
                + "try{Object.defineProperty(HTMLScriptElement.prototype,'crossOrigin',{set:function(){},get:function(){return null;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(HTMLLinkElement.prototype,'crossOrigin',{set:function(){},get:function(){return null;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(HTMLScriptElement.prototype,'integrity',{set:function(){},get:function(){return '';},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(HTMLLinkElement.prototype,'integrity',{set:function(){},get:function(){return '';},configurable:true});}catch(e){}"
                + "try{"
                + "var _origSetAttr=Element.prototype.setAttribute;"
                + "Element.prototype.setAttribute=function(n,v){"
                + "var nl=n.toLowerCase();"
                + "if(nl==='crossorigin'||nl==='integrity'){"
                + "var t=this.tagName&&this.tagName.toUpperCase();"
                + "if(t==='SCRIPT'||t==='LINK')return;"
                + "}"
                + "if((nl==='src'||nl==='href')&&typeof v==='string'&&v.length>0){"
                + "var t=this.tagName&&this.tagName.toUpperCase();"
                + "if(t==='SCRIPT'||t==='LINK'||t==='IMG'||t==='VIDEO'||t==='AUDIO'||t==='SOURCE'){"
                + "v=proxyUrl(v);"
                + "}"
                + "}"
                + "return _origSetAttr.call(this,n,v);"
                + "};"
                + "}catch(e){}"
                + "try{"
                + "var _scriptSrcDesc=Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype,'src');"
                + "if(_scriptSrcDesc&&_scriptSrcDesc.set){"
                + "var _origScriptSrcSet=_scriptSrcDesc.set;"
                + "Object.defineProperty(HTMLScriptElement.prototype,'src',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origScriptSrcSet.call(this,v);},"
                + "get:_scriptSrcDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "try{"
                + "var _linkHrefDesc=Object.getOwnPropertyDescriptor(HTMLLinkElement.prototype,'href');"
                + "if(_linkHrefDesc&&_linkHrefDesc.set){"
                + "var _origLinkHrefSet=_linkHrefDesc.set;"
                + "Object.defineProperty(HTMLLinkElement.prototype,'href',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origLinkHrefSet.call(this,v);},"
                + "get:_linkHrefDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "function patchScriptEl(el){"
                + "if(!el||!el.tagName)return;"
                + "var tag=el.tagName.toUpperCase();"
                + "if(tag!=='SCRIPT'&&tag!=='LINK')return;"
                + "try{if(el.hasAttribute('crossorigin'))el.removeAttribute('crossorigin');}catch(e){}"
                + "try{if(el.hasAttribute('integrity'))el.removeAttribute('integrity');}catch(e){}"
                + "var srcAttr=tag==='SCRIPT'?'src':'href';"
                + "try{var sv=el.getAttribute(srcAttr);if(sv&&sv.indexOf('http')===0&&sv.indexOf('/api/browser/')===-1){el.setAttribute(srcAttr,proxyUrl(sv));}}catch(e){}"
                + "try{var pv=el[srcAttr];if(pv&&typeof pv==='string'&&pv.indexOf('http')===0&&pv.indexOf('/api/browser/')===-1){el[srcAttr]=proxyUrl(pv);}}catch(e){}"
                + "}"
                + "var _appendChild=Node.prototype.appendChild;"
                + "Node.prototype.appendChild=function(child){"
                + "if(child&&child.nodeType===1){patchScriptEl(child);}"
                + "return _appendChild.call(this,child);"
                + "};"
                + "var _insertBefore=Node.prototype.insertBefore;"
                + "Node.prototype.insertBefore=function(child,ref){"
                + "if(child&&child.nodeType===1){patchScriptEl(child);}"
                + "return _insertBefore.call(this,child,ref);"
                + "};"
                + "var _lastReload=0;"
                + "var _origLocReload=Location.prototype.reload;"
                + "Location.prototype.reload=function(){"
                + "var now=Date.now();"
                + "if(now-_lastReload<3000){console.log('[Proxy] blocked rapid reload');return;}"
                + "_lastReload=now;"
                + "_origLocReload.call(this);"
                + "};"
                + "new MutationObserver(function(ml){"
                + "for(var i=0;i<ml.length;i++){"
                + "var m=ml[i];"
                + "if(m.type==='childList'&&m.addedNodes){"
                + "for(var j=0;j<m.addedNodes.length;j++){"
                + "var n=m.addedNodes[j];"
                + "if(n.nodeType===1){proxyNodeAttrs(n);var sub=n.querySelectorAll&&n.querySelectorAll('img,video,audio,source,script,link');if(sub){for(var k=0;k<sub.length;k++){proxyNodeAttrs(sub[k]);}}}"
                + "}"
                + "}"
                + "}"
                + "}).observe(document.documentElement,{childList:true,subtree:true});"
                + "try{if(navigator.serviceWorker){"
                + "Object.defineProperty(navigator,'serviceWorker',{get:function(){return{register:function(){return Promise.reject(new Error('disabled'));},ready:Promise.resolve({unregister:function(){return Promise.resolve(true);}}),controller:null,addEventListener:function(){},removeEventListener:function(){}};},configurable:true});"
                + "}}catch(e){}"
                + "window.addEventListener('beforeunload',function(e){"
                + "try{var href=window.location.href;if(href.indexOf('/api/browser/')===-1){e.preventDefault();window.location.href=proxyUrl(href);}}catch(ex){}"
                + "},false);"
                + "})();</script>";

        html = removeCspMeta(html);
        html = removeBaseTags(html);
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
        html = html.replaceAll("(<script\\b[^>]*?)\\s+nonce=\"[^\"]*\"", "$1");
        html = html.replaceAll("(<link\\b[^>]*?)\\s+nonce=\"[^\"]*\"", "$1");
        html = html.replaceAll("(<script\\b[^>]*?)\\s+onerror=\"[^\"]*\"", "$1");

        html = rewriteIframeSrc(html, originalUrl, proxyBaseUrl);

        if (html.contains("<head>")) {
            html = html.replace("<head>", "<head>" + script);
        } else if (html.contains("<HEAD>")) {
            html = html.replace("<HEAD>", "<HEAD>" + script);
        } else {
            html = script + html;
        }

        return html;
    }

    private String removeCspMeta(String html) {
        return html.replaceAll("(?is)<meta\\b(?=[^>]*http-equiv\\s*=\\s*(['\"]?)Content-Security-Policy\\1)[^>]*>", "");
    }

    private String removeBaseTags(String html) {
        return html.replaceAll("(?is)<base\\b[^>]*>", "");
    }

    private String rewriteIframeSrc(String html, String baseUrl, String proxyBaseUrl) {
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
            String proxied = buildProxyUrl(resolved, proxyBaseUrl);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + proxied + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String rewriteHtmlResourceAttrs(String html, String baseUrl, String proxyBaseUrl) {
        String rewritten = rewriteHtmlUrlAttr(html, baseUrl, "src", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "href", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "poster", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "action", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "formaction", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "data-src", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "data-original", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "data-lazy-src", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "data-bg", proxyBaseUrl);
        rewritten = rewriteHtmlUrlAttr(rewritten, baseUrl, "data-srcset", proxyBaseUrl);
        rewritten = rewriteHtmlSrcsetAttr(rewritten, baseUrl, proxyBaseUrl);
        return rewritten;
    }

    private String rewriteHtmlUrlAttr(String html, String baseUrl, String attrName, String proxyBaseUrl) {
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
                    || value.contains("/api/browser/empty")
                    || value.contains("/api/browser/")) {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + value + quote));
                continue;
            }
            String decodedValue = value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
            String resolved;
            try {
                resolved = new URL(new URL(baseUrl), decodedValue).toString();
            } catch (Exception e) {
                resolved = decodedValue;
            }
            String proxied = buildProxyUrl(resolved, proxyBaseUrl);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + proxied + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String rewriteHtmlSrcsetAttr(String html, String baseUrl, String proxyBaseUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\ssrcset\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String prefix = m.group(1);
            String quote = m.group(2).startsWith("\"") ? "\"" : "'";
            String value = m.group(3) != null ? m.group(3) : m.group(4);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(prefix + quote + rewriteSrcset(value, baseUrl, proxyBaseUrl) + quote));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String rewriteSrcset(String value, String baseUrl, String proxyBaseUrl) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String[] items = value.split(",");
        StringBuilder rewritten = new StringBuilder();
        for (String item : items) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            String proxied = proxyAttributeUrl(parts[0], baseUrl, proxyBaseUrl);
            if (rewritten.length() > 0) {
                rewritten.append(", ");
            }
            rewritten.append(proxied);
            if (parts.length > 1) {
                rewritten.append(" ").append(parts[1]);
            }
        }
        return rewritten.toString();
    }

    private String rewriteCssUrls(String css, String baseUrl, String proxyBaseUrl) {
        String rewritten = rewriteCssFunctionUrls(css, baseUrl, proxyBaseUrl);
        return rewriteCssImportUrls(rewritten, baseUrl, proxyBaseUrl);
    }

    private String rewriteCssFunctionUrls(String css, String baseUrl, String proxyBaseUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "url\\(\\s*(['\"]?)(.*?)\\1\\s*\\)",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(css == null ? "" : css);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String raw = m.group(2);
            String proxied = proxyAttributeUrl(raw, baseUrl, proxyBaseUrl);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("url(\"" + proxied + "\")"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String rewriteCssImportUrls(String css, String baseUrl, String proxyBaseUrl) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "@import\\s+(['\"])(.*?)\\1",
                java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(css == null ? "" : css);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String proxied = proxyAttributeUrl(m.group(2), baseUrl, proxyBaseUrl);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("@import \"" + proxied + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String proxyAttributeUrl(String value, String baseUrl, String proxyBaseUrl) {
        if (value == null || value.isEmpty()
                || value.startsWith("#")
                || value.startsWith("data:")
                || value.startsWith("blob:")
                || value.startsWith("javascript:")
                || value.contains("/api/browser/proxy")
                || value.contains("/api/browser/empty")) {
            return value;
        }
        String resolved;
        try {
            resolved = new URL(new URL(baseUrl), value).toString();
        } catch (Exception e) {
            resolved = value;
        }
        return buildProxyUrl(resolved, proxyBaseUrl);
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

    private String extractFirstPlayUrl(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        if (!json.contains("\"code\":0") && !json.contains("\"code\": 0")) {
            return "";
        }
        java.util.regex.Pattern[] patterns = new java.util.regex.Pattern[]{
                java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"(https?://[^\"]+)\""),
                java.util.regex.Pattern.compile("\"url\"\\s*:\\s*\"(https?:\\\\/\\\\/[^\"]+)\""),
                java.util.regex.Pattern.compile("\"baseUrl\"\\s*:\\s*\"(https?://[^\"]+)\""),
                java.util.regex.Pattern.compile("\"baseUrl\"\\s*:\\s*\"(https?:\\\\/\\\\/[^\"]+)\"")
        };
        String fallback = "";
        for (java.util.regex.Pattern pattern : patterns) {
            java.util.regex.Matcher matcher = pattern.matcher(json);
            while (matcher.find()) {
                String candidate = unescapeJson(matcher.group(1));
                if (fallback.isEmpty()) {
                    fallback = candidate;
                }
                if (candidate.contains("bilivideo.com") || candidate.contains("hdslb.com")
                        || candidate.contains("acgvideo.com") || candidate.contains(".mp4")
                        || candidate.contains(".flv") || candidate.contains(".m4s")) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    private String extractVideoCid(String viewJson) {
        if (viewJson == null || viewJson.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher pagesMatcher = java.util.regex.Pattern.compile(
                "\"pages\"\\s*:\\s*\\[\\s*\\{[^\\]]*?\"cid\"\\s*:\\s*(\\d+)"
        ).matcher(viewJson);
        if (pagesMatcher.find()) {
            return pagesMatcher.group(1);
        }
        java.util.regex.Matcher dataMatcher = java.util.regex.Pattern.compile(
                "\"data\"\\s*:\\s*\\{[^\\}]*?\"cid\"\\s*:\\s*(\\d+)"
        ).matcher(viewJson);
        if (dataMatcher.find()) {
            return dataMatcher.group(1);
        }
        return extractJsonNumber(viewJson, "cid");
    }

    private List<Map<String, String>> parseBingHtmlResults(String html) {
        List<Map<String, String>> results = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)<li class=\"b_algo\"[^>]*>.*?<h2[^>]*>\\s*<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?<div class=\"b_caption\"[^>]*>\\s*<p[^>]*>(.*?)</p>"
        ).matcher(html == null ? "" : html);
        while (matcher.find() && results.size() < 12) {
            String link = stripHtml(unescapeXml(matcher.group(1)));
            String title = stripHtml(matcher.group(2));
            String snippet = stripHtml(matcher.group(3));
            if (link.startsWith("http") && !title.isBlank()) {
                results.add(Map.of(
                        "title", title,
                        "url", link,
                        "snippet", snippet.length() > 180 ? snippet.substring(0, 180) : snippet
                ));
            }
        }
        return results;
    }

    private List<Map<String, String>> parseBingRssResults(String xml) {
        List<Map<String, String>> results = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)<item>\\s*<title>(.*?)</title>\\s*<link>(.*?)</link>.*?<description>(.*?)</description>.*?</item>"
        ).matcher(xml == null ? "" : xml);
        while (matcher.find() && results.size() < 12) {
            String title = stripHtml(unescapeXml(matcher.group(1)));
            String url = stripHtml(unescapeXml(matcher.group(2)));
            String snippet = stripHtml(unescapeXml(matcher.group(3)));
            if (!title.isBlank() && !url.isBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                results.add(Map.of(
                        "title", title,
                        "url", url,
                        "snippet", snippet.length() > 180 ? snippet.substring(0, 180) : snippet
                ));
            }
        }
        return results;
    }

    private String unescapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
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
        String result = value
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .replace("\\r", " ")
                .replace("\\t", " ");
        java.util.regex.Matcher unicodeMatcher = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(result);
        StringBuffer usb = new StringBuffer();
        while (unicodeMatcher.find()) {
            char ch = (char) Integer.parseInt(unicodeMatcher.group(1), 16);
            unicodeMatcher.appendReplacement(usb, java.util.regex.Matcher.quoteReplacement(String.valueOf(ch)));
        }
        unicodeMatcher.appendTail(usb);
        return usb.toString();
    }

    private String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</script>", "<\\/script>");
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
