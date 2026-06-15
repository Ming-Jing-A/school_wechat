package com.mingjin.school_wechat.controller;

import com.mingjin.school_wechat.filter.ProxyMultipartBypassFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

@Slf4j
@RestController
@RequestMapping("/api/browser")
public class BrowserProxyController {

    private static SSLContext createSSLContext() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());
            return sslContext;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SSL context", e);
        }
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .sslContext(createSSLContext())
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ConcurrentHashMap<String, String> cookieStore = new ConcurrentHashMap<>();

    public String getCookiesForUrl(String url) {
        try {
            return getCookiesForUri(safeCreateUri(url));
        } catch (Exception e) {
            return "";
        }
    }

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

    @RequestMapping(value = "/manga-images", method = RequestMethod.GET, produces = "application/json;charset=UTF-8")
    public ResponseEntity<byte[]> mangaImages(@RequestParam("ep_id") String epId) {
        try {
            String apiUrl = "https://manga.bilibili.com/twirp/comic.v1.Comic/GetImageIndex?ep_id=" + encodeURIComponent(epId);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://manga.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String json = new String(resp.body(), StandardCharsets.UTF_8);

            java.util.List<String> images = new java.util.ArrayList<>();
            java.util.regex.Matcher imgMatcher = java.util.regex.Pattern.compile(
                    "\"path\"\\s*:\\s*\"([^\"]+)\""
            ).matcher(json);
            while (imgMatcher.find()) {
                String path = unescapeJson(imgMatcher.group(1));
                if (!path.isEmpty()) {
                    String imgUrl;
                    if (path.startsWith("http")) {
                        imgUrl = path;
                    } else if (path.startsWith("//")) {
                        imgUrl = "https:" + path;
                    } else {
                        imgUrl = "https://manga.hdslb.com" + (path.startsWith("/") ? path : "/" + path);
                    }
                    images.add(buildProxyUrl(imgUrl, ""));
                }
            }

            if (images.isEmpty()) {
                java.util.regex.Matcher urlMatcher = java.util.regex.Pattern.compile(
                        "\"url\"\\s*:\\s*\"(https?://[^\"]+)\""
                ).matcher(json);
                while (urlMatcher.find()) {
                    String imgUrl = unescapeJson(urlMatcher.group(1));
                    if (!imgUrl.isEmpty()) {
                        images.add(buildProxyUrl(imgUrl, ""));
                    }
                }
            }

            if (images.isEmpty()) {
                try {
                    String readApiUrl = "https://manga.bilibili.com/twirp/comic.v1.Comic/GetImageToken?urls=" + encodeURIComponent("[\"" + "https://manga.hdslb.com" + "\"]");
                    HttpRequest readReq = HttpRequest.newBuilder()
                            .uri(URI.create(readApiUrl))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://manga.bilibili.com/")
                            .header("Accept", "application/json,*/*")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"ep_id\":" + epId + "}"))
                            .build();
                    HttpResponse<byte[]> readResp = httpClient.send(readReq, HttpResponse.BodyHandlers.ofByteArray());
                    String readJson = new String(readResp.body(), StandardCharsets.UTF_8);
                    java.util.regex.Matcher tokenMatcher = java.util.regex.Pattern.compile(
                            "\"url\"\\s*:\\s*\"([^\"]+)\".*?\"token\"\\s*:\\s*\"([^\"]+)\""
                    ).matcher(readJson);
                    while (tokenMatcher.find()) {
                        String imgUrl = unescapeJson(tokenMatcher.group(1));
                        String token = unescapeJson(tokenMatcher.group(2));
                        if (!imgUrl.isEmpty()) {
                            String fullUrl = imgUrl + (imgUrl.contains("?") ? "&" : "?") + "token=" + encodeURIComponent(token);
                            images.add(buildProxyUrl(fullUrl, ""));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to get manga image tokens: {}", e.getMessage());
                }
            }

            StringBuilder jsonSb = new StringBuilder("{\"images\":[");
            for (int i = 0; i < images.size(); i++) {
                if (i > 0) jsonSb.append(",");
                jsonSb.append("\"").append(escapeJs(images.get(i))).append("\"");
            }
            jsonSb.append("],\"count\":").append(images.size());
            if (images.isEmpty()) {
                jsonSb.append(",\"error\":\"无法获取漫画图片，可能需要登录或漫画不可用\"");
            }
            jsonSb.append("}");

            return ResponseEntity.ok().header("Content-Type", "application/json;charset=UTF-8")
                    .body(jsonSb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            String errorJson = "{\"images\":[],\"count\":0,\"error\":\"" + escapeJs(ex.getMessage() != null ? ex.getMessage() : "获取失败") + "\"}";
            return ResponseEntity.ok().header("Content-Type", "application/json;charset=UTF-8")
                    .body(errorJson.getBytes(StandardCharsets.UTF_8));
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
        sb.append("window.addEventListener('message',function(e){");
        sb.append("if(e.data&&e.data.type==='browser_pause_media'){");
        sb.append("try{");
        sb.append("var p=document.getElementById('player');if(p&&!p.paused)p.pause();");
        sb.append("document.querySelectorAll('video').forEach(function(v){if(!v.paused)v.pause();});");
        sb.append("document.querySelectorAll('audio').forEach(function(a){if(!a.paused)a.pause();});");
        sb.append("document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage({type:'browser_pause_media'},'*');}catch(ex){}});");
        sb.append("}catch(ex){}");
        sb.append("}");
        sb.append("},false);");
        sb.append("</script></body></html>");
        return sb.toString();
    }

    private String buildErrorPage(String message) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<style>body{display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:system-ui;color:#999;background:#000}"
                + "p{font-size:14px}</style></head>"
                + "<body><p>" + escapeHtml(message) + "</p></body></html>";
    }

    /**
     * 专用m3u8代理端点：获取m3u8内容，重写.ts URL为stream代理URL，返回修改后的m3u8
     */
    @GetMapping("/m3u8-proxy")
    public void m3u8Proxy(@RequestParam String url, HttpServletResponse servletResponse) {
        try {
            log.info("[M3U8-Proxy] Fetching m3u8: {}", url);
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(safeCreateUri(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "http://www.yinghuajinju.com/")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String m3u8Content = resp.body();
            log.info("[M3U8-Proxy] Fetched m3u8, status={}, len={}", resp.statusCode(), m3u8Content.length());

            // 重写m3u8中的URL
            String rewritten = rewriteM3u8Content(m3u8Content, url);
            log.info("[M3U8-Proxy] Rewritten m3u8 len={}", rewritten.length());

            servletResponse.setStatus(200);
            servletResponse.setContentType("application/vnd.apple.mpegurl");
            servletResponse.setCharacterEncoding("UTF-8");
            servletResponse.addHeader("Access-Control-Allow-Origin", "*");
            servletResponse.addHeader("Access-Control-Allow-Credentials", "true");
            servletResponse.addHeader("Cache-Control", "no-cache");
            servletResponse.getWriter().write(rewritten);
        } catch (Exception e) {
            log.error("[M3U8-Proxy] Error: {}", e.getMessage());
            try {
                servletResponse.setStatus(500);
                servletResponse.setContentType("text/plain");
                servletResponse.getWriter().write("M3U8 proxy error: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    @GetMapping("/yinghua-video-src")
    public ResponseEntity<Map<String, Object>> yinghuaVideoSrc(@RequestParam String url) {
        try {
            log.info("[YinghuaVideoSrc] Fetching video source for: {}", url);
            // 获取视频页面HTML
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(safeCreateUri(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            String html = resp.body();
            log.info("[YinghuaVideoSrc] Fetched page, status={}, len={}", resp.statusCode(), html.length());

            // 提取视频源URL
            String videoSrcUrl = null;

            // 模式A: data-vid属性
            java.util.regex.Matcher dataVidMatcher = java.util.regex.Pattern.compile(
                    "data-vid=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(html);
            if (dataVidMatcher.find()) {
                videoSrcUrl = dataVidMatcher.group(1).replaceAll("\\$mp4$", "");
                log.info("[YinghuaVideoSrc] Found video URL via data-vid: {}", videoSrcUrl);
            }

            // 模式B: changeplay() 函数调用
            if (videoSrcUrl == null) {
                java.util.regex.Matcher changeplayMatcher = java.util.regex.Pattern.compile(
                        "changeplay\\(['\"]([^'\"]+)['\"]\\)", java.util.regex.Pattern.CASE_INSENSITIVE
                ).matcher(html);
                if (changeplayMatcher.find()) {
                    videoSrcUrl = changeplayMatcher.group(1).replaceAll("\\$mp4$", "");
                    log.info("[YinghuaVideoSrc] Found video URL via changeplay: {}", videoSrcUrl);
                }
            }

            if (videoSrcUrl == null) {
                log.warn("[YinghuaVideoSrc] No video source found for: {}", url);
                return ResponseEntity.ok(Map.of("success", false, "message", "未找到视频源"));
            }

            // 构建代理视频URL
            String proxiedVideoUrl;
            if (videoSrcUrl.toLowerCase().contains(".m3u8")) {
                proxiedVideoUrl = "/api/browser/m3u8-proxy?url=" + encodeURIComponent(videoSrcUrl);
            } else {
                proxiedVideoUrl = "/api/browser/stream?url=" + encodeURIComponent(videoSrcUrl);
            }

            // 提取标题
            String title = "樱花动漫";
            java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile(
                    "<title[^>]*>([^<]+)</title>", java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(html);
            if (titleMatcher.find()) {
                title = titleMatcher.group(1).trim();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "videoUrl", proxiedVideoUrl,
                    "title", title,
                    "isHls", videoSrcUrl.toLowerCase().contains(".m3u8")
            ));
        } catch (Exception e) {
            log.error("[YinghuaVideoSrc] Error: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of("success", false, "message", "获取视频源失败: " + e.getMessage()));
        }
    }

    private String rewriteM3u8Content(String m3u8Content, String m3u8Url) {
        StringBuilder result = new StringBuilder();
        String[] lines = m3u8Content.split("\n");
        try {
            java.net.URL baseUrl = new URL(m3u8Url);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    // 处理 #EXT-X-KEY 中的 URI 属性
                    if (trimmed.contains("URI=")) {
                        java.util.regex.Matcher uriMatcher = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(line);
                        StringBuffer sb = new StringBuffer();
                        while (uriMatcher.find()) {
                            String keyUrl = uriMatcher.group(1);
                            try {
                                keyUrl = new URL(baseUrl, keyUrl).toString();
                            } catch (Exception e) { /* already absolute */ }
                            uriMatcher.appendReplacement(sb, "URI=\"/api/browser/stream?url=" + encodeURIComponent(keyUrl) + "\"");
                        }
                        uriMatcher.appendTail(sb);
                        line = sb.toString();
                    }
                    result.append(line).append("\n");
                } else {
                    // 这是一个URL行（.ts分片或子m3u8）
                    try {
                        String absoluteUrl = new URL(baseUrl, trimmed).toString();
                        if (absoluteUrl.toLowerCase().contains(".m3u8")) {
                            result.append("/api/browser/m3u8-proxy?url=").append(encodeURIComponent(absoluteUrl)).append("\n");
                        } else {
                            result.append("/api/browser/stream?url=").append(encodeURIComponent(absoluteUrl)).append("\n");
                        }
                    } catch (Exception e) {
                        result.append(line).append("\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[M3U8-Proxy] Failed to rewrite m3u8, returning original: {}", e.getMessage());
            return m3u8Content;
        }
        return result.toString();
    }

    @RequestMapping(value = "/stream", method = {RequestMethod.GET, RequestMethod.HEAD})
    public void streamProxy(
            @RequestParam String url,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        try {
            boolean streamIsIqiyi = isIqiyiUrl(url);
            boolean streamIsYinghua = isYinghuaUrl(url);
            if (streamIsIqiyi) {
                log.info("Iqiyi stream proxy: method={}, range={}", servletRequest.getMethod(), servletRequest.getHeader("Range"));
            }
            if (streamIsYinghua) {
                log.info("[M3U8/TS] Stream proxy for yinghua CDN: url={}", url);
            }
            
            java.net.http.HttpRequest.Builder reqBuilder = java.net.http.HttpRequest.newBuilder()
                    .uri(safeCreateUri(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

            String referer;
            String origin;
            if (isBilibiliVideoCdnUrl(url)) {
                referer = "https://www.bilibili.com/";
                origin = "https://www.bilibili.com";
            } else if (streamIsIqiyi) {
                referer = "https://www.iqiyi.com/";
                origin = "https://www.iqiyi.com";
            } else if (streamIsYinghua) {
                referer = "http://www.yinghuajinju.com/";
                origin = "http://www.yinghuajinju.com";
            } else {
                referer = extractReferer(url);
                origin = extractOrigin(url);
            }
            reqBuilder.header("Referer", referer);
            reqBuilder.header("Origin", origin);
            reqBuilder.header("Accept-Encoding", "identity");

            String serverCookies = getCookiesForUri(safeCreateUri(url));
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

            if (streamIsIqiyi) {
                String iqCt = response.headers().firstValue("Content-Type").orElse("");
                String iqContentLen = response.headers().firstValue("Content-Length").orElse("");
                String iqContentRange = response.headers().firstValue("Content-Range").orElse("");
                String iqAcceptRange = response.headers().firstValue("Accept-Ranges").orElse("");
                log.info("Iqiyi stream resp: status={}, ct={}, contentLen={}, contentRange={}, acceptRange={}",
                        statusCode, iqCt, iqContentLen, iqContentRange, iqAcceptRange);
            }

            servletResponse.setStatus(statusCode);

            String contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            servletResponse.setContentType(contentType);

            List<String> setCookies = response.headers().allValues("Set-Cookie");
            if (!setCookies.isEmpty()) {
                storeCookies(safeCreateUri(url), setCookies);
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
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin, Cache-Control");
        return new ResponseEntity<>("{}", headers, HttpStatus.OK);
    }

    @RequestMapping(value = "/proxy", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.OPTIONS})
    public ResponseEntity<byte[]> proxy(@RequestParam String url, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        String method = servletRequest.getMethod();
        if ("OPTIONS".equals(servletRequest.getMethod())) {
            HttpHeaders corsHeaders = new HttpHeaders();
            corsHeaders.set("Access-Control-Allow-Origin", "*");
            corsHeaders.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            String requestHeaders = servletRequest.getHeader("Access-Control-Request-Headers");
            if (requestHeaders != null && !requestHeaders.isEmpty()) {
                corsHeaders.set("Access-Control-Allow-Headers", requestHeaders);
            } else {
                corsHeaders.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin, Cache-Control, X-CSRF-Token, X-XSRF-Token, X-ACS-Action, X-ACS-Version, X-ACS-Signature-Nonce, X-ACS-Date, X-ACS-Accesskey-Id, X-ACS-Security-Token");
            }
            corsHeaders.set("Access-Control-Max-Age", "86400");
            corsHeaders.set("Access-Control-Allow-Credentials", "true");
            return new ResponseEntity<>(corsHeaders, HttpStatus.OK);
        }

        if (isBilibiliLogUrl(url)) {
            log.debug("Blocking Bilibili log URL: {}", url);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Access-Control-Allow-Origin", "*");
            return new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
        }

        if (isBingTrackingUrl(url)) {
            log.debug("Blocking Bing tracking URL: {}", url);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Access-Control-Allow-Origin", "*");
            return new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
        }

        if (isByteDanceTrackingUrl(url)) {
            log.debug("Blocking ByteDance tracking URL: {}", url);
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            headers.set("Access-Control-Allow-Origin", "*");
            return new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
        }

        try {
            URI uri = safeCreateUri(url);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return ResponseEntity.badRequest().build();
            }

            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            if (isLocalhostOrPrivateIp(host)) {
                log.debug("Skipping localhost/private IP: {}", url);
                HttpHeaders skipHeaders = new HttpHeaders();
                skipHeaders.set("Content-Type", "application/json");
                skipHeaders.set("Access-Control-Allow-Origin", "*");
                return new ResponseEntity<>("{}".getBytes(StandardCharsets.UTF_8), skipHeaders, HttpStatus.OK);
            }

            if (isBilibiliSearchUrl(url)) {
                return handleBilibiliSearchProxy(url, servletRequest);
            }

            String proxyBaseUrl = resolveProxyBaseUrl(servletRequest);

            boolean isDouyin = isDouyinUrl(url);
            boolean isDoubao = isDoubaoUrl(url);
            boolean isQwen = isQwenUrl(url);
            boolean isYuanbao = isYuanbaoUrl(url);
            boolean isByteDanceRelated = isByteDanceRelatedUrl(url);
            boolean isAiSite = isDoubao || isQwen || isYuanbao;
            boolean isBilibili = isBilibiliUrl(url);
            boolean isBing = isBingUrl(url);
            boolean isIqiyi = isIqiyiUrl(url);
            boolean isYinghua = isYinghuaUrl(url);
            boolean skipFurtherProcessing = false;
            if (isIqiyi) {
                boolean isVs = isVideoStreamUrl(url);
                log.info("Iqiyi proxy: {} {}, ct={}, range={}, isVideoStream={}", method, url,
                        servletRequest.getContentType(), servletRequest.getHeader("Range"), isVs);
            }
            boolean isVerifyRequest = url.toLowerCase().contains("verify.zijieapi.com");
            boolean isCaptchaVerify = isVerifyRequest && url.toLowerCase().contains("captcha/verify");
            if (isVerifyRequest) {
                log.debug("Verify request: {} {} (isCaptchaVerify={})", method, url, isCaptchaVerify);
            }
            if (isByteDanceRelated && "PUT".equals(method)) {
                log.debug("ByteDance PUT request: url={}, contentType={}, contentLength={}",
                        url, servletRequest.getContentType(), servletRequest.getContentLength());
            }
            if (isAiSite) {
                log.debug("AI site proxy request: {} {} (doubao={}, qwen={}, yuanbao={})", method, url, isDoubao, isQwen, isYuanbao);
            }
            String origUserAgent = servletRequest.getHeader("User-Agent");
            String userAgent;
            if (isDouyin) {
                userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
            } else if ((isDoubao || isByteDanceRelated || isVerifyRequest) && origUserAgent != null && !origUserAgent.isEmpty()) {
                userAgent = origUserAgent;
            } else {
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
            }
            String referer;
            if (isBilibili) {
                referer = "https://www.bilibili.com/";
            } else if (isBing) {
                referer = "https://cn.bing.com/";
            } else if (isDoubao || isByteDanceRelated) {
                referer = "https://www.doubao.com/";
            } else if (isQwen) {
                referer = "https://tongyi.aliyun.com/";
            } else if (isYuanbao) {
                referer = "https://yuanbao.tencent.com/";
            } else if (isIqiyi) {
                referer = "https://www.iqiyi.com/";
            } else if (isYinghua) {
                referer = "http://www.yinghuajinju.com/";
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
                } else if (isDoubao || isByteDanceRelated) {
                    origin = "https://www.doubao.com";
                } else if (isQwen) {
                    origin = "https://tongyi.aliyun.com";
                } else if (isYuanbao) {
                    origin = "https://yuanbao.tencent.com";
                } else if (isIqiyi) {
                    origin = "https://www.iqiyi.com";
                } else if (isYinghua) {
                    origin = "http://www.yinghuajinju.com";
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
            if ((isIqiyi || isYinghua) && isVideoStreamUrl(url)) {
                reqBuilder.header("Accept-Encoding", "identity");
            } else {
                reqBuilder.header("Accept-Encoding", "gzip, deflate");
            }

            String serverCookies = getCookiesForUri(uri);
            if (isByteDanceRelated && serverCookies.isEmpty()) {
                String doubaoCookies = getCookiesForUri(URI.create("https://www.doubao.com"));
                if (!doubaoCookies.isEmpty()) {
                    serverCookies = doubaoCookies;
                }
            }
            String browserCookies = servletRequest.getHeader("Cookie");
            if (browserCookies != null && !browserCookies.isEmpty()) {
                if (serverCookies.isEmpty()) {
                    serverCookies = browserCookies;
                } else {
                    java.util.Map<String, String> mergedCookies = new java.util.LinkedHashMap<>();
                    for (String part : serverCookies.split(";\\s*")) {
                        int eq = part.indexOf('=');
                        if (eq > 0) mergedCookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                    }
                    for (String part : browserCookies.split(";\\s*")) {
                        int eq = part.indexOf('=');
                        if (eq > 0) mergedCookies.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                    }
                    StringBuilder csb = new StringBuilder();
                    mergedCookies.forEach((k, v) -> {
                        if (csb.length() > 0) csb.append("; ");
                        csb.append(k).append("=").append(v);
                    });
                    serverCookies = csb.toString();
                }
            }
            if (!serverCookies.isEmpty()) {
                reqBuilder.header("Cookie", serverCookies);
            }

            String authorization = servletRequest.getHeader("Authorization");
            if (authorization != null && !authorization.isEmpty()) {
                reqBuilder.header("Authorization", authorization);
            }

            if (url.contains("ApplyImageUpload") || url.contains("ApplyUpload") || url.contains("CommitImageUpload") || url.contains("CommitUpload")) {
                log.info("Upload API: {} {}", method, url);
            }

            if (isAiSite || isByteDanceRelated) {
                java.util.List<String> forwardPrefixes = java.util.List.of(
                        "x-xsrf-token", "x-csrf-token", "x-csrf", "x-requested-with",
                        "x-acw-ts", "x-acw-sign", "x-sgext", "x-sign", "x-token",
                        "x-tc-traceid", "x-tc-action", "x-tc-version", "x-tc-requestid",
                        "x-acs-action", "x-acs-version", "x-acs-signature-nonce",
                        "x-acs-date", "x-acs-accesskey-id", "x-acs-security-token",
                        "x-acs-content-sha256",
                        "x-amz-date", "x-amz-security-token", "x-amz-content-sha256",
                        "x-date", "x-content-sha256",
                        "x-secsdk-csrf", "x-secsdk", "x-secnode",
                        "x-bd-traceid", "x-bd-logid", "x-tt",
                        "x-use-csrf",
                        "x-tos-",
                        "content-crc32", "content-crc64", "content-md5",
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
                                    if ((hLower.equals("authorization") && authorization != null && !authorization.isEmpty())
                                            || hLower.equals("cookie")) {
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
            String requestContentType = (String) servletRequest.getAttribute(ProxyMultipartBypassFilter.ORIGINAL_CONTENT_TYPE_ATTR);
            if (requestContentType == null) {
                requestContentType = servletRequest.getContentType();
            }
            if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
                requestBody = servletRequest.getInputStream().readAllBytes();
                if (requestContentType != null && !requestContentType.isEmpty()) {
                    reqBuilder.header("Content-Type", requestContentType);
                }
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody != null ? requestBody : new byte[0]));
                
                if (url.contains("CommitImageUpload") || url.contains("CommitUpload")) {
                    String reqBodyPreview = "";
                    if (requestBody != null && requestBody.length > 0) {
                        try { reqBodyPreview = new String(requestBody, 0, Math.min(requestBody.length, 1000), StandardCharsets.UTF_8); } catch (Exception ignored) {}
                    }
                    String authHeader = servletRequest.getHeader("Authorization");
                    String authPreview = authHeader != null && authHeader.length() > 300 ? authHeader.substring(0, 300) + "..." : authHeader;
                    log.info("CommitUpload req: ct={}, auth={}, bodyLen={}, body={}", requestContentType, authPreview, requestBody != null ? requestBody.length : 0, reqBodyPreview);
                }
            } else {
                reqBuilder.method(method, HttpRequest.BodyPublishers.ofByteArray(new byte[0]));
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
                log.debug("AI chat API detected, enabling SSE streaming: {} {}", method, url);
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
                        log.debug("SSE stream ended for: {}", url);
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
                        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin, Cache-Control");
                        headers.set("Access-Control-Allow-Credentials", "true");
                        return new ResponseEntity<>(body, headers, HttpStatus.valueOf(statusCode));
                    }
                }
            }

            boolean isUploadRequest = url.contains("snssdk.com") || url.contains("douyin.com/upload") || url.contains("volcengineapi.com") || url.contains("ivolces.com") || url.contains("bytecdn.cn") || url.contains("pstatp.com") || url.contains("bytedancevod.com");
            String uploadPhase = null;
            if (isUploadRequest) {
                if (url.contains("phase=init")) uploadPhase = "init";
                else if (url.contains("phase=merge")) uploadPhase = "merge";
                else if (url.contains("phase=upload") || url.contains("partNumber=")) uploadPhase = "part";
                else if (url.contains("CommitImageUpload") || url.contains("CommitUpload")) uploadPhase = "commit";
                else uploadPhase = "upload";
            }
            if (isUploadRequest && ("PUT".equals(method) || "POST".equals(method))) {
                reqBuilder.timeout(Duration.ofMinutes(5));
            }

            HttpRequest httpRequest = reqBuilder.build();

            if (isVideoStream) {
                try {
                    HttpResponse<java.io.InputStream> streamResponse = httpClient.send(httpRequest,
                            HttpResponse.BodyHandlers.ofInputStream());
                    int streamStatusCode = streamResponse.statusCode();

                    if (isIqiyi) {
                        String sCt = streamResponse.headers().firstValue("Content-Type").orElse("");
                        String sCl = streamResponse.headers().firstValue("Content-Length").orElse("");
                        String sCr = streamResponse.headers().firstValue("Content-Range").orElse("");
                        String sAr = streamResponse.headers().firstValue("Accept-Ranges").orElse("");
                        log.info("Iqiyi stream passthrough: status={}, ct={}, contentLen={}, contentRange={}, acceptRange={}",
                                streamStatusCode, sCt, sCl, sCr, sAr);
                    }

                    servletResponse.setStatus(streamStatusCode);

                    String streamCt = streamResponse.headers().firstValue("Content-Type").orElse("application/octet-stream");
                    servletResponse.setContentType(streamCt);
                    streamResponse.headers().firstValue("Content-Length")
                            .ifPresent(cl -> servletResponse.setHeader("Content-Length", cl));
                    streamResponse.headers().firstValue("Content-Range")
                            .ifPresent(cr -> servletResponse.setHeader("Content-Range", cr));
                    streamResponse.headers().firstValue("Accept-Ranges")
                            .ifPresent(ar -> servletResponse.setHeader("Accept-Ranges", ar));
                    streamResponse.headers().firstValue("ETag")
                            .ifPresent(et -> servletResponse.setHeader("ETag", et));
                    streamResponse.headers().firstValue("Last-Modified")
                            .ifPresent(lm -> servletResponse.setHeader("Last-Modified", lm));
                    streamResponse.headers().firstValue("Cache-Control")
                            .ifPresent(cc -> servletResponse.setHeader("Cache-Control", cc));

                    servletResponse.setHeader("Access-Control-Allow-Origin", "*");
                    servletResponse.setHeader("Access-Control-Allow-Credentials", "true");
                    servletResponse.setHeader("Access-Control-Expose-Headers",
                            "Content-Length, Content-Range, Accept-Ranges, ETag");

                    try (java.io.InputStream is = streamResponse.body();
                         java.io.OutputStream os = servletResponse.getOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                    return null;
                } catch (Exception e) {
                    log.error("Video stream proxy failed: method={}, url={}, error={}", method, url, e.getMessage());
                    HttpHeaders errHeaders = new HttpHeaders();
                    errHeaders.set("Content-Type", "text/plain; charset=utf-8");
                    errHeaders.set("Access-Control-Allow-Origin", "*");
                    return new ResponseEntity<>(
                            ("Video stream error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8),
                            errHeaders, HttpStatus.BAD_GATEWAY);
                }
            }

            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            } catch (Exception e) {
                log.error("Proxy request failed: method={}, url={}, error={}", method, url, e.getMessage());
                if (isUploadRequest) {
                    HttpHeaders errHeaders = new HttpHeaders();
                    errHeaders.set("Content-Type", "application/json");
                    errHeaders.set("Access-Control-Allow-Origin", "*");
                    return new ResponseEntity<>(
                            ("{\"error\":\"proxy request failed: " + e.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8),
                            errHeaders, HttpStatus.BAD_GATEWAY);
                }
                throw e;
            }

            int statusCode = response.statusCode();

            if (isIqiyi) {
                String iqCt = response.headers().firstValue("Content-Type").orElse("");
                String iqContentRange = response.headers().firstValue("Content-Range").orElse("");
                String iqAcceptRange = response.headers().firstValue("Accept-Ranges").orElse("");
                String iqContentLen = response.headers().firstValue("Content-Length").orElse("");
                String iqCacheCtrl = response.headers().firstValue("Cache-Control").orElse("");
                byte[] iqBody = response.body();
                String iqBodyPreview = "";
                try { if (iqBody != null && iqBody.length > 0) { iqBodyPreview = new String(iqBody, 0, Math.min(iqBody.length, 500), StandardCharsets.UTF_8); } } catch (Exception ignored) {}
                log.info("Iqiyi resp: status={}, ct={}, contentLen={}, contentRange={}, acceptRange={}, cacheCtrl={}, body={}",
                        statusCode, iqCt, iqContentLen, iqContentRange, iqAcceptRange, iqCacheCtrl, iqBodyPreview);
            }

            if (uploadPhase != null) {
                log.info("Upload {}: status={}, method={}, url={}", uploadPhase, statusCode, method, url);
                byte[] respBody = response.body();
                String bodyPreview = "";
                try { if (respBody != null && respBody.length > 0) { bodyPreview = new String(respBody, 0, Math.min(respBody.length, 500), StandardCharsets.UTF_8); } } catch (Exception ignored) {}
                String ct = response.headers().firstValue("Content-Type").orElse("");
                String etag = response.headers().firstValue("ETag").orElse("");
                String contentLen = response.headers().firstValue("Content-Length").orElse("");
                if (statusCode != 200) {
                    log.warn("Upload {} failed: ct={}, etag={}, contentLen={}, body={}", uploadPhase, ct, etag, contentLen, bodyPreview);
                } else {
                    log.info("Upload {} resp: ct={}, etag={}, contentLen={}, body={}", uploadPhase, ct, etag, contentLen, bodyPreview);
                }
            }

            if (isAiSite && statusCode != 200) {
                byte[] respBody = response.body();
                String bodyPreview = "";
                try { if (respBody != null && respBody.length > 0) { bodyPreview = new String(respBody, 0, Math.min(respBody.length, 500), StandardCharsets.UTF_8); } } catch (Exception ignored) {}
                log.warn("AI site non-200 response: status={}, method={}, url={}, contentType={}, body={}",
                        statusCode, method, url, response.headers().firstValue("Content-Type").orElse(""), bodyPreview);
            }

            if (url.contains("ApplyImageUpload") || url.contains("ApplyUpload") || url.contains("CommitImageUpload") || url.contains("CommitUpload")) {
                byte[] apiRespBody = response.body();
                String apiBodyPreview = "";
                try { if (apiRespBody != null && apiRespBody.length > 0) { apiBodyPreview = new String(apiRespBody, 0, Math.min(apiRespBody.length, 500), StandardCharsets.UTF_8); } } catch (Exception ignored) {}
                String apiCt = response.headers().firstValue("Content-Type").orElse("");
                log.info("Upload API resp: status={}, ct={}, body={}", statusCode, apiCt, apiBodyPreview);
            }

            if (statusCode != 200 && (url.contains("zijieapi.com") || url.contains("byteimg.com") || url.contains("catpcha"))) {
                log.warn("Verify-related non-200: status={}, url={}", statusCode, url);
            }

            if (isByteDanceRelated && ("PUT".equals(method) || "POST".equals(method)) && requestBody != null && requestBody.length > 0) {
                boolean isMonitoring = url.contains("mcs.zijieapi.com") || url.contains("mcs.doubao.com");
                if (!isMonitoring && statusCode != 200) {
                    byte[] respBody = response.body();
                    String bodyPreview = "";
                    try { if (respBody != null && respBody.length > 0) { bodyPreview = new String(respBody, 0, Math.min(respBody.length, 500), StandardCharsets.UTF_8); } } catch (Exception ignored) {}
                    log.warn("Upload non-200: status={}, method={}, url={}, respBody={}",
                            statusCode, method, url, bodyPreview);
                }
            }

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

            boolean isVideoStreamPassthrough = isVideoStreamUrl(url) && !isM3u8Content(
                    response.headers().firstValue("Content-Type").orElse(""), url);
            if (isVideoStreamPassthrough) {
                if (isIqiyi) {
                    String vCt = response.headers().firstValue("Content-Type").orElse("");
                    String vCl = response.headers().firstValue("Content-Length").orElse("");
                    String vCr = response.headers().firstValue("Content-Range").orElse("");
                    log.info("Iqiyi video stream passthrough: status={}, ct={}, contentLen={}, contentRange={}, bodyLen={}",
                            statusCode, vCt, vCl, vCr, response.body().length);
                }
                HttpHeaders streamHeaders = new HttpHeaders();
                response.headers().map().forEach((k, vs) -> {
                    String lower = k.toLowerCase();
                    if (!lower.equals("access-control-allow-origin") && !lower.equals("access-control-allow-credentials")) {
                        vs.forEach(v -> streamHeaders.add(k, v));
                    }
                });
                streamHeaders.set("Access-Control-Allow-Origin", "*");
                streamHeaders.set("Access-Control-Allow-Credentials", "true");
                streamHeaders.set("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges, ETag");
                return new ResponseEntity<>(response.body(), streamHeaders, HttpStatus.valueOf(statusCode));
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
                if (isIqiyi) {
                    log.warn("Iqiyi brotli response cannot be decompressed (no brotli lib), len={}", body.length);
                }
            }
            String respContentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            boolean bodyModified = false;

            if (isVerifyRequest) {
                String bodyPreview = "";
                try { if (body != null && body.length > 0) { bodyPreview = new String(body, 0, Math.min(body.length, 500), StandardCharsets.UTF_8); } } catch (Exception ignored) {}
                log.info("Verify response: status={}, contentType={}, body={}, url={}", statusCode, respContentType, bodyPreview, url);
            }

            if (respContentType.contains("text/html")) {
                String html = new String(body, resolveCharset(respContentType, body));
                if (isVerifyRequest && html.trim().startsWith("{")) {
                    if (isCaptchaVerify) {
                        respContentType = "application/json; charset=utf-8";
                        log.info("Verify captcha/verify JSON-in-HTML: no URL rewrite (preserve token), bodyLen={}", html.length());
                    } else {
                        String rewritten = rewriteTextBodyUrls(html, url, proxyBaseUrl);
                        body = rewritten.getBytes(StandardCharsets.UTF_8);
                        bodyModified = true;
                        respContentType = "application/json; charset=utf-8";
                        log.info("Verify JSON-in-HTML: URLs rewritten (no HTML injection), urlsFound={}", !rewritten.equals(html));
                    }
                } else if (isAiSite) {
                    log.debug("AI site HTML response: status={}, bodyLength={}, hasBody={}, url={}", statusCode, body.length, !html.trim().isEmpty(), url);
                }
                if (!(isVerifyRequest && html.trim().startsWith("{"))) {
                    if (isBilibiliVideoUrl(url)) {
                        html = injectBilibiliVideoPlayer(html, url, proxyBaseUrl);
                        body = html.getBytes(StandardCharsets.UTF_8);
                        bodyModified = true;
                    } else if (isBilibiliMangaUrl(url)) {
                        html = injectBilibiliMangaPage(url);
                        body = html.getBytes(StandardCharsets.UTF_8);
                        bodyModified = true;
                    } else if (isBilibiliMatchUrl(url)) {
                        html = injectBilibiliMatchPage(url);
                        body = html.getBytes(StandardCharsets.UTF_8);
                        bodyModified = true;
                    } else {
                        // 樱花动漫：视频页面在HTML被修改前提取视频源，用自定义播放器播放
                        String yinghuaOriginalHtml = null;
                        if (isYinghua && isYinghuaVideoPageUrl(url)) {
                            yinghuaOriginalHtml = html; // 保存原始HTML用于提取iframe
                        }
                        if (isBilibiliSearchUrl(url)) {
                            html = stripPageScripts(html);
                        }
                        html = rewriteHtmlResourceAttrs(html, url, proxyBaseUrl);
                        html = modifyHtml(html, url, proxyBaseUrl);
                        // 爱奇艺视频页面注入播放辅助脚本
                        if (isIqiyi && isIqiyiVideoPageUrl(url)) {
                            html = injectIqiyiPlayerHelper(html);
                        }
                        // 樱花动漫：用原始HTML提取视频源，返回自定义播放器
                        if (yinghuaOriginalHtml != null) {
                            try {
                                String customPlayerHtml = buildYinghuaCustomPlayer(yinghuaOriginalHtml, url, proxyBaseUrl);
                                if (customPlayerHtml != null) {
                                    body = customPlayerHtml.getBytes(StandardCharsets.UTF_8);
                                    bodyModified = true;
                                    respContentType = "text/html;charset=UTF-8";
                                    log.info("[Yinghua] Replaced video page with custom player for url={}", url);
                                    skipFurtherProcessing = true;
                                }
                            } catch (Exception e) {
                                log.warn("[Yinghua] Failed to build custom player, falling back to proxy: {}", e.getMessage());
                            }
                        }
                        if (!skipFurtherProcessing) {
                            body = html.getBytes(StandardCharsets.UTF_8);
                            bodyModified = true;
                        }
                    }
                }
            } else if (respContentType.contains("text/css")) {
                String css = new String(body, resolveCharset(respContentType, body));
                css = rewriteCssUrls(css, url, proxyBaseUrl);
                body = css.getBytes(StandardCharsets.UTF_8);
                bodyModified = true;
            } else if (isM3u8Content(respContentType, url)) {
                // m3u8 文件：重写其中的 URL（相对路径转绝对路径再转代理URL）
                String m3u8 = new String(body, StandardCharsets.UTF_8);
                m3u8 = rewriteM3u8Urls(m3u8, url, proxyBaseUrl);
                body = m3u8.getBytes(StandardCharsets.UTF_8);
                respContentType = "application/vnd.apple.mpegurl";
                bodyModified = true;
                log.info("[M3U8] Rewritten m3u8 content for url={}, bodyLen={}", url, body.length);
            } else if (shouldRewriteTextBody(respContentType, isBilibili, isBing, isDoubao, isQwen, isYuanbao)) {
                String text = new String(body, resolveCharset(respContentType, body));
                text = rewriteTextBodyUrls(text, url, proxyBaseUrl);
                body = text.getBytes(StandardCharsets.UTF_8);
                bodyModified = true;
            } else if (respContentType.contains("application/json")) {
                boolean bodyIsJson = body != null && body.length > 0;
                if (bodyIsJson) {
                    byte firstByte = body[0];
                    bodyIsJson = firstByte == '{' || firstByte == '[' || firstByte == '"' || firstByte == 'n' || firstByte == 't' || firstByte == 'f';
                }
                if (!bodyIsJson) {
                    if (isIqiyi) {
                        log.info("Iqiyi binary response (not JSON): ct={}, bodyLen={}, skipping URL rewrite", respContentType, body != null ? body.length : 0);
                    }
                } else {
                    String json = new String(body, resolveCharset(respContentType, body));
                    if (isCaptchaVerify) {
                        log.info("Verify captcha/verify response: status={}, bodyLen={}, body={}", statusCode, json.length(), json.substring(0, Math.min(json.length(), 500)));
                    }
                    if (isByteDanceRelated && (url.contains("ApplyImageUpload") || url.contains("ApplyUpload") || url.contains("CommitImageUpload") || url.contains("CommitUpload"))) {
                        rewriteUploadUrls(json, proxyBaseUrl);
                    } else if (isUploadRequest) {
                    } else if (isIqiyi && isIqiyiVideoDataApi(url)) {
                        // 爱奇艺视频数据API返回的流URL包含签名token，重写会导致播放失败
                        log.info("Iqiyi video data API: skipping URL rewrite, bodyLen={}", json.length());
                    } else if (!isCaptchaVerify && !isDoubao) {
                        String rewritten = rewriteTextBodyUrls(json, url, proxyBaseUrl);
                        if (!rewritten.equals(json)) {
                            if (isVerifyRequest) {
                                String preview = rewritten.substring(0, Math.min(rewritten.length(), 500));
                                log.info("Verify JSON rewritten: preview={}", preview);
                            }
                            if (isIqiyi) {
                                log.info("Iqiyi dispatch JSON rewritten: urlsRewritten=true, bodyLen={}", json.length());
                            }
                            body = rewritten.getBytes(StandardCharsets.UTF_8);
                            bodyModified = true;
                        } else if (isVerifyRequest) {
                            log.info("Verify JSON NOT rewritten (no URLs found), contentType={}, bodyLen={}", respContentType, json.length());
                        }
                    } else if (isDoubao) {
                        String rewritten = rewriteTextBodyUrls(json, url, proxyBaseUrl);
                        if (!rewritten.equals(json)) {
                            body = rewritten.getBytes(StandardCharsets.UTF_8);
                            bodyModified = true;
                        }
                    }
                }
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", respContentType);
            if (respContentType.contains("text/html")) {
                headers.set("Content-Security-Policy", "frame-ancestors *");
            }
            headers.set("Access-Control-Allow-Origin", "*");
            headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS");
            headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With, Accept, Origin, Cache-Control");
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
        } catch (java.net.ConnectException e) {
            log.warn("Proxy connect failed for url={}: {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(("Connection failed: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Proxy timeout for url={}: {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(("Request timeout: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } catch (javax.net.ssl.SSLHandshakeException e) {
            log.warn("Proxy SSL error for url={}: {}", url, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .body(("SSL error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Proxy error for url={}: {} - {}", url, e.getClass().getSimpleName(), e.getMessage());
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
            // m3u8 文件需要走 proxy 端点做 URL 重写，不能走 stream 端点
            if (targetUrl.toLowerCase().contains(".m3u8")) {
                return "/api/browser/proxy?url=" + encodeURIComponent(targetUrl);
            }
            return "/api/browser/stream?url=" + encodeURIComponent(targetUrl);
        }
        return "/api/browser/proxy?url=" + encodeURIComponent(targetUrl);
    }

    private String extractReferer(String url) {
        try {
            URI u = safeCreateUri(url);
            return u.getScheme() + "://" + u.getAuthority() + u.getPath();
        } catch (Exception e) {
            return url;
        }
    }

    private String extractOrigin(String url) {
        try {
            URI u = safeCreateUri(url);
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
                URI searchUri = safeCreateUri(url);
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
            if (host == null || !host.endsWith(".bilibili.com") || path == null) return false;
            if (java.util.regex.Pattern.compile("/video/(BV[a-zA-Z0-9]+|av\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(path).find()) return true;
            if (path.startsWith("/bangumi/play/")) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBilibiliBangumiUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && host.endsWith(".bilibili.com") && path != null && path.startsWith("/bangumi/play/");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBilibiliMangaUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return host != null && (host.equalsIgnoreCase("manga.bilibili.com") || host.endsWith(".bilibili.com"))
                    && path != null && path.startsWith("/manga/");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBilibiliMatchUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if (host != null && host.equalsIgnoreCase("sports.bilibili.com")) return true;
            if (host != null && host.endsWith(".bilibili.com") && path != null && path.startsWith("/match/")) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String injectBilibiliVideoPlayer(String html, String url, String proxyBaseUrl) {
        if (isBilibiliBangumiUrl(url)) {
            return injectBilibiliBangumiPlayer(url);
        }
        String playPageUrl = "/api/browser/bilibili-play-page?url=" + encodeURIComponent(url);
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<title>Bilibili Player</title>"
                + "<style>*{margin:0;padding:0}html,body,iframe{width:100%;height:100%;border:none;overflow:hidden}</style>"
                + "</head><body>"
                + "<iframe src='" + escapeJs(playPageUrl) + "' allow='autoplay;fullscreen;encrypted-media;picture-in-picture' allowfullscreen></iframe>"
                + "</body></html>";
    }

    private String injectBilibiliBangumiPlayer(String url) {
        try {
            String epId = "";
            String ssId = "";
            java.util.regex.Matcher epMatcher = java.util.regex.Pattern.compile("/bangumi/play/(ep\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (epMatcher.find()) {
                epId = epMatcher.group(1).substring(2);
            }
            java.util.regex.Matcher ssMatcher = java.util.regex.Pattern.compile("/bangumi/play/(ss\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (ssMatcher.find()) {
                ssId = ssMatcher.group(1).substring(2);
            }

            String apiUrl;
            if (!epId.isEmpty()) {
                apiUrl = "https://api.bilibili.com/pgc/view/web/season?ep_id=" + encodeURIComponent(epId);
            } else if (!ssId.isEmpty()) {
                apiUrl = "https://api.bilibili.com/pgc/view/web/season?season_id=" + encodeURIComponent(ssId);
            } else {
                return buildBangumiErrorPage("无法识别番剧ID");
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json,*/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String json = new String(resp.body(), StandardCharsets.UTF_8);

            String title = unescapeJson(extractJsonString(json, "\"title\""));
            String cover = unescapeJson(extractJsonString(json, "\"cover\""));

            java.util.List<java.util.Map<String, String>> episodes = new java.util.ArrayList<>();
            java.util.regex.Matcher epListMatcher = java.util.regex.Pattern.compile(
                    "\"episodes\"\\s*:\\s*\\[([\\s\\S]*?)\\]"
            ).matcher(json);
            if (epListMatcher.find()) {
                String epListStr = epListMatcher.group(1);
                java.util.regex.Matcher epItemMatcher = java.util.regex.Pattern.compile(
                        "\"id\"\\s*:\\s*(\\d+).*?\"title\"\\s*:\\s*\"([^\"]*?)\".*?\"long_title\"\\s*:\\s*\"([^\"]*?)\".*?\"bvid\"\\s*:\\s*\"([^\"]+?)\".*?\"cid\"\\s*:\\s*(\\d+)"
                ).matcher(epListStr);
                while (epItemMatcher.find()) {
                    java.util.Map<String, String> ep = new java.util.LinkedHashMap<>();
                    ep.put("epId", epItemMatcher.group(1));
                    ep.put("title", unescapeJson(epItemMatcher.group(2)));
                    ep.put("longTitle", unescapeJson(epItemMatcher.group(3)));
                    ep.put("bvid", epItemMatcher.group(4));
                    ep.put("cid", epItemMatcher.group(5));
                    episodes.add(ep);
                }
            }

            String firstBvid = episodes.isEmpty() ? "" : episodes.get(0).get("bvid");
            String firstCid = episodes.isEmpty() ? "" : episodes.get(0).get("cid");

            String proxyVideoUrl = "";
            if (!firstBvid.isEmpty() && !firstCid.isEmpty()) {
                proxyVideoUrl = resolveBilibiliStreamUrl(firstBvid, firstCid);
            }

            String posterUrl = cover.isEmpty() ? "" : buildProxyUrl(cover, "");

            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
            sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
            sb.append("<title>").append(escapeHtml(title)).append(" - 番剧</title>");
            sb.append("<style>");
            sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
            sb.append("html,body{width:100%;height:100%;overflow:hidden;background:#000;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#fff;}");
            sb.append(".container{display:flex;width:100%;height:100%;}");
            sb.append(".video-area{flex:1;display:flex;flex-direction:column;min-width:0;}");
            sb.append(".video-header{padding:12px 16px;background:#111;display:flex;align-items:center;gap:12px;}");
            sb.append(".video-title{font-size:16px;font-weight:600;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}");
            sb.append(".back-btn{background:none;border:1px solid #444;color:#fff;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:13px;}");
            sb.append(".back-btn:hover{background:#333;}");
            sb.append(".video-wrap{flex:1;position:relative;background:#000;display:flex;align-items:center;justify-content:center;}");
            sb.append("video{width:100%;height:100%;object-fit:contain;}");
            sb.append(".ep-sidebar{width:280px;background:#111;border-left:1px solid #222;overflow-y:auto;flex-shrink:0;}");
            sb.append(".ep-header{padding:12px 16px;font-size:14px;font-weight:600;border-bottom:1px solid #222;position:sticky;top:0;background:#111;z-index:1;}");
            sb.append(".ep-item{padding:10px 16px;cursor:pointer;border-bottom:1px solid #1a1a1a;font-size:13px;transition:background .15s;}");
            sb.append(".ep-item:hover{background:#222;}");
            sb.append(".ep-item.active{background:#00a1d6;color:#fff;}");
            sb.append(".ep-item .ep-title{font-weight:500;}");
            sb.append(".ep-item .ep-sub{font-size:11px;color:#999;margin-top:2px;}");
            sb.append(".ep-item.active .ep-sub{color:#ccc;}");
            sb.append(".error{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;color:#999;gap:12px;}");
            sb.append(".error p{font-size:14px;}");
            sb.append(".retry-btn{background:#00a1d6;color:#fff;border:none;padding:8px 20px;border-radius:4px;cursor:pointer;font-size:13px;}");
            sb.append(".retry-btn:hover{background:#00b5e5;}");
            sb.append(".loading-overlay{position:absolute;top:0;left:0;right:0;bottom:0;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,0.6);z-index:5;}");
            sb.append(".loading-overlay.show{display:flex;}");
            sb.append(".spinner{width:36px;height:36px;border:3px solid rgba(255,255,255,0.2);border-top-color:#00a1d6;border-radius:50%;animation:spin .8s linear infinite;}");
            sb.append("@keyframes spin{to{transform:rotate(360deg)}}");
            sb.append("</style></head><body>");

            sb.append("<div class='container'>");
            sb.append("<div class='video-area'>");
            sb.append("<div class='video-header'>");
            sb.append("<button class='back-btn' onclick='goBack()'>← 返回</button>");
            sb.append("<div class='video-title' id='videoTitle'>").append(escapeHtml(title)).append("</div>");
            sb.append("</div>");
            sb.append("<div class='video-wrap'>");
            if (proxyVideoUrl.isEmpty()) {
                sb.append("<div class='error'><p>无法获取视频流</p><p style='font-size:12px;color:#666'>番剧可能需要大会员或视频不可用</p><button class='retry-btn' onclick='retryWithPlayer()'>使用B站播放器</button></div>");
            } else {
                sb.append("<video id='player' controls playsinline autoplay preload='auto'");
                if (!posterUrl.isEmpty()) sb.append(" poster='").append(posterUrl).append("'");
                sb.append(" src='").append(escapeHtml(proxyVideoUrl)).append("'></video>");
            }
            sb.append("<div class='loading-overlay' id='loadingOverlay'><div class='spinner'></div></div>");
            sb.append("</div>");
            sb.append("</div>");

            if (!episodes.isEmpty()) {
                sb.append("<div class='ep-sidebar'>");
                sb.append("<div class='ep-header'>选集 (").append(episodes.size()).append(")</div>");
                for (int i = 0; i < episodes.size(); i++) {
                    java.util.Map<String, String> ep = episodes.get(i);
                    String epTitle = ep.get("title");
                    String epLongTitle = ep.get("longTitle");
                    String epBvid = ep.get("bvid");
                    String epCid = ep.get("cid");
                    String displayTitle = (epLongTitle != null && !epLongTitle.isEmpty()) ? epLongTitle : epTitle;
                    boolean isActive = (i == 0);
                    sb.append("<div class='ep-item").append(isActive ? " active" : "").append("' data-bvid='").append(escapeHtml(epBvid)).append("' data-cid='").append(escapeHtml(epCid)).append("' data-title='").append(escapeHtml(displayTitle)).append("' onclick='switchEp(this)'>");
                    sb.append("<div class='ep-title'>").append(escapeHtml(epTitle));
                    if (!epLongTitle.isEmpty()) {
                        sb.append(" ").append(escapeHtml(epLongTitle));
                    }
                    sb.append("</div>");
                    sb.append("</div>");
                }
                sb.append("</div>");
            }

            sb.append("</div>");

            sb.append("<script>");
            sb.append("function goBack(){try{window.parent.postMessage({type:'browser_go_back'},'*');}catch(e){try{history.back();}catch(e2){}}}");
            sb.append("function showLoading(show){var ol=document.getElementById('loadingOverlay');if(ol){if(show){ol.classList.add('show');}else{ol.classList.remove('show');}}}");
            sb.append("function switchEp(el){");
            sb.append("var bvid=el.getAttribute('data-bvid');");
            sb.append("var cid=el.getAttribute('data-cid');");
            sb.append("var title=el.getAttribute('data-title');");
            sb.append("var items=document.querySelectorAll('.ep-item');");
            sb.append("for(var i=0;i<items.length;i++){items[i].classList.remove('active');}");
            sb.append("el.classList.add('active');");
            sb.append("document.getElementById('videoTitle').textContent=title;");
            sb.append("showLoading(true);");
            sb.append("fetch('/api/browser/bilibili-video-stream?bvid='+encodeURIComponent(bvid)+'&cid='+encodeURIComponent(cid)+'&qn=64')");
            sb.append(".then(function(r){return r.json();})");
            sb.append(".then(function(data){");
            sb.append("if(data.url){");
            sb.append("var player=document.getElementById('player');");
            sb.append("if(!player){");
            sb.append("var area=document.querySelector('.video-wrap');");
            sb.append("area.innerHTML='<video id=\"player\" controls playsinline autoplay preload=\"auto\" src=\"'+data.url+'\"></video><div class=\"loading-overlay\" id=\"loadingOverlay\"><div class=\"spinner\"></div></div>';");
            sb.append("player=document.getElementById('player');");
            sb.append("}");
            sb.append("player.src=data.url;");
            sb.append("player.play().catch(function(){});");
            sb.append("}else{");
            sb.append("retryWithPlayer();}");
            sb.append("showLoading(false);");
            sb.append("}).catch(function(e){showLoading(false);retryWithPlayer();});");
            sb.append("}");

            sb.append("function retryWithPlayer(){");
            sb.append("var area=document.querySelector('.video-wrap');");
            sb.append("var iframe=document.createElement('iframe');");
            sb.append("iframe.style.cssText='width:100%;height:100%;border:none';");
            sb.append("iframe.allow='autoplay;fullscreen;encrypted-media;picture-in-picture';");
            sb.append("iframe.setAttribute('allowfullscreen','');");
            sb.append("var activeItem=document.querySelector('.ep-item.active');");
            sb.append("var bvid=activeItem?activeItem.getAttribute('data-bvid'):'';");
            sb.append("var q=bvid?'bvid='+encodeURIComponent(bvid):'';");
            sb.append("iframe.src='/api/browser/proxy?url='+encodeURIComponent('https://player.bilibili.com/player.html?isOutside=true&'+q+'&autoplay=1&high_quality=1');");
            sb.append("area.innerHTML='';area.appendChild(iframe);");
            sb.append("}");

            sb.append("var player=document.getElementById('player');");
            sb.append("if(player){");
            sb.append("player.addEventListener('error',function(){retryWithPlayer();});");
            sb.append("player.addEventListener('canplay',function(){showLoading(false);try{player.play();}catch(e){}});");
            sb.append("player.addEventListener('loadeddata',function(){showLoading(false);try{player.play();}catch(e){}});");
            sb.append("try{player.play();}catch(e){}");
            sb.append("}");
            sb.append("window.addEventListener('message',function(e){if(e.data&&e.data.type==='browser_pause_media'){try{var p=document.getElementById('player');if(p&&!p.paused)p.pause();document.querySelectorAll('video').forEach(function(v){if(!v.paused)v.pause();});document.querySelectorAll('audio').forEach(function(a){if(!a.paused)a.pause();});document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage({type:'browser_pause_media'},'*');}catch(ex){}});}catch(ex){}}});");
            sb.append("</script></body></html>");
            return sb.toString();
        } catch (Exception e) {
            return buildBangumiErrorPage(e.getMessage() != null ? e.getMessage() : "番剧加载失败");
        }
    }

    private String resolveBilibiliStreamUrl(String bvid, String cid) {
        try {
            int[] qnLevels = {64, 32, 16};
            for (int qn : qnLevels) {
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
                if (!streamUrl.isEmpty()) {
                    return buildProxyUrl(streamUrl, "");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve stream URL for bvid={}, cid={}: {}", bvid, cid, e.getMessage());
        }
        return "";
    }

    private String buildBangumiErrorPage(String message) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>"
                + "<style>body{display:flex;justify-content:center;align-items:center;height:100vh;margin:0;font-family:system-ui;color:#999;background:#000;flex-direction:column;gap:12px;}"
                + "p{font-size:14px;}.retry-btn{background:#00a1d6;color:#fff;border:none;padding:8px 20px;border-radius:4px;cursor:pointer;font-size:13px;margin-top:8px;}"
                + ".back-btn{background:none;border:1px solid #444;color:#fff;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:13px;}</style></head>"
                + "<body><p>" + escapeHtml(message) + "</p>"
                + "<button class='back-btn' onclick='try{window.parent.postMessage({type:\"browser_go_back\"},\"*\");}catch(e){history.back();}'>返回</button>"
                + "</body></html>";
    }

    private String injectBilibiliMangaPage(String url) {
        try {
            String mangaId = "";
            String epId = "";
            java.util.regex.Matcher mcMatcher = java.util.regex.Pattern.compile("/manga/(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (mcMatcher.find()) {
                mangaId = mcMatcher.group(1);
            }
            java.util.regex.Matcher epMatcher = java.util.regex.Pattern.compile("/manga/detail/\\w+\\?ep_id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (epMatcher.find()) {
                epId = epMatcher.group(1);
            }
            if (mangaId.isEmpty() && epId.isEmpty()) {
                java.util.regex.Matcher mc2Matcher = java.util.regex.Pattern.compile("id=(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(url);
                if (mc2Matcher.find()) {
                    mangaId = mc2Matcher.group(1);
                }
            }

            String apiUrl = "";
            if (!epId.isEmpty()) {
                apiUrl = "https://manga.bilibili.com/twirp/comic.v1.Comic/GetEpisode?ep_id=" + epId;
            } else if (!mangaId.isEmpty()) {
                apiUrl = "https://manga.bilibili.com/twirp/comic.v1.Comic/ComicDetail?comic_id=" + mangaId;
            }

            String title = "哔哩哔哩漫画";
            String cover = "";
            String author = "";
            String description = "";
            java.util.List<java.util.Map<String, String>> epList = new java.util.ArrayList<>();

            if (!apiUrl.isEmpty()) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://manga.bilibili.com/")
                            .header("Accept", "application/json,*/*")
                            .GET()
                            .build();
                    HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    String json = new String(resp.body(), StandardCharsets.UTF_8);

                    title = unescapeJson(extractJsonString(json, "\"title\""));
                    cover = unescapeJson(extractJsonString(json, "\"vertical_cover\""));
                    if (cover.isEmpty()) cover = unescapeJson(extractJsonString(json, "\"cover\""));
                    author = unescapeJson(extractJsonString(json, "\"author_name\""));
                    description = unescapeJson(extractJsonString(json, "\"classic_lines\""));
                    if (description.isEmpty()) description = unescapeJson(extractJsonString(json, "\"evaluate\""));

                    java.util.regex.Matcher epListMatcher = java.util.regex.Pattern.compile(
                            "\"ep_list\"\\s*:\\s*\\[([\\s\\S]*?)\\]"
                    ).matcher(json);
                    if (epListMatcher.find()) {
                        String epListStr = epListMatcher.group(1);
                        java.util.regex.Matcher epItemMatcher = java.util.regex.Pattern.compile(
                                "\"id\"\\s*:\\s*(\\d+).*?\"short_title\"\\s*:\\s*\"([^\"]*?)\".*?\"title\"\\s*:\\s*\"([^\"]*?)\""
                        ).matcher(epListStr);
                        while (epItemMatcher.find()) {
                            java.util.Map<String, String> ep = new java.util.LinkedHashMap<>();
                            ep.put("id", epItemMatcher.group(1));
                            ep.put("shortTitle", unescapeJson(epItemMatcher.group(2)));
                            ep.put("title", unescapeJson(epItemMatcher.group(3)));
                            epList.add(ep);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch manga API: {}", e.getMessage());
                }
            }

            String proxyCover = cover.isEmpty() ? "" : buildProxyUrl(cover, "");

            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
            sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
            sb.append("<title>").append(escapeHtml(title)).append(" - 漫画</title>");
            sb.append("<style>");
            sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
            sb.append("html,body{width:100%;height:100%;overflow:hidden;background:#1a1a1a;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#fff;}");
            sb.append(".container{display:flex;width:100%;height:100%;}");
            sb.append(".main{flex:1;display:flex;flex-direction:column;overflow:hidden;}");
            sb.append(".header{padding:12px 16px;background:#222;display:flex;align-items:center;gap:12px;flex-shrink:0;}");
            sb.append(".back-btn{background:none;border:1px solid #444;color:#fff;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:13px;}");
            sb.append(".back-btn:hover{background:#333;}");
            sb.append(".header-title{font-size:16px;font-weight:600;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}");
            sb.append(".content{flex:1;overflow-y:auto;padding:16px;}");
            sb.append(".detail{display:flex;gap:16px;margin-bottom:20px;}");
            sb.append(".cover-img{width:140px;height:190px;border-radius:6px;object-fit:cover;flex-shrink:0;background:#333;}");
            sb.append(".info{flex:1;display:flex;flex-direction:column;gap:6px;}");
            sb.append(".info h1{font-size:18px;font-weight:600;}");
            sb.append(".info .author{font-size:13px;color:#aaa;}");
            sb.append(".info .desc{font-size:13px;color:#999;line-height:1.5;overflow:hidden;text-overflow:ellipsis;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;}");
            sb.append(".ep-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(80px,1fr));gap:8px;}");
            sb.append(".ep-card{background:#2a2a2a;border-radius:6px;padding:8px 10px;cursor:pointer;text-align:center;font-size:12px;transition:background .15s;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}");
            sb.append(".ep-card:hover{background:#333;}");
            sb.append(".ep-card.active{background:#00a1d6;color:#fff;}");
            sb.append(".reader-area{flex:1;display:flex;flex-direction:column;align-items:center;overflow-y:auto;padding:16px;gap:8px;}");
            sb.append(".reader-area img{max-width:100%;border-radius:4px;background:#333;}");
            sb.append(".loading{display:flex;align-items:center;justify-content:center;height:200px;color:#666;font-size:14px;}");
            sb.append(".empty{display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;color:#666;gap:12px;}");
            sb.append(".empty p{font-size:14px;}");
            sb.append(".sidebar{width:240px;background:#222;border-left:1px solid #333;overflow-y:auto;flex-shrink:0;}");
            sb.append(".sidebar-header{padding:12px 16px;font-size:14px;font-weight:600;border-bottom:1px solid #333;position:sticky;top:0;background:#222;z-index:1;}");
            sb.append(".sidebar-item{padding:10px 16px;cursor:pointer;border-bottom:1px solid #2a2a2a;font-size:13px;transition:background .15s;}");
            sb.append(".sidebar-item:hover{background:#2a2a2a;}");
            sb.append(".sidebar-item.active{background:#00a1d6;color:#fff;}");
            sb.append(".spinner{width:24px;height:24px;border:2px solid rgba(255,255,255,0.2);border-top-color:#00a1d6;border-radius:50%;animation:spin .8s linear infinite;margin:0 auto;}");
            sb.append("@keyframes spin{to{transform:rotate(360deg)}}");
            sb.append("</style></head><body>");

            sb.append("<div class='container'>");
            sb.append("<div class='main'>");
            sb.append("<div class='header'>");
            sb.append("<button class='back-btn' onclick='goBack()'>← 返回</button>");
            sb.append("<div class='header-title' id='headerTitle'>").append(escapeHtml(title)).append("</div>");
            sb.append("</div>");

            sb.append("<div id='viewDetail' class='content'>");
            sb.append("<div class='detail'>");
            if (!proxyCover.isEmpty()) {
                sb.append("<img class='cover-img' src='").append(proxyCover).append("' alt=''>");
            }
            sb.append("<div class='info'>");
            sb.append("<h1>").append(escapeHtml(title)).append("</h1>");
            if (!author.isEmpty()) sb.append("<div class='author'>").append(escapeHtml(author)).append("</div>");
            if (!description.isEmpty()) sb.append("<div class='desc'>").append(escapeHtml(description)).append("</div>");
            sb.append("</div></div>");

            if (!epList.isEmpty()) {
                sb.append("<div class='ep-grid'>");
                for (int i = 0; i < Math.min(epList.size(), 200); i++) {
                    java.util.Map<String, String> ep = epList.get(i);
                    String epIdStr = ep.get("id");
                    String shortTitle = ep.get("shortTitle");
                    String epTitle = ep.get("title");
                    String label = !shortTitle.isEmpty() ? shortTitle : epTitle;
                    if (label.isEmpty()) label = "第" + (i + 1) + "话";
                    sb.append("<div class='ep-card' data-epid='").append(escapeHtml(epIdStr)).append("' onclick='readEp(this)'>").append(escapeHtml(label)).append("</div>");
                }
                sb.append("</div>");
            } else {
                sb.append("<div class='empty'><p>暂无章节信息</p></div>");
            }
            sb.append("</div>");

            sb.append("<div id='viewReader' class='reader-area' style='display:none'></div>");

            sb.append("</div>");

            if (!epList.isEmpty()) {
                sb.append("<div class='sidebar'>");
                sb.append("<div class='sidebar-header'>章节列表 (").append(epList.size()).append(")</div>");
                for (int i = 0; i < Math.min(epList.size(), 200); i++) {
                    java.util.Map<String, String> ep = epList.get(i);
                    String epIdStr = ep.get("id");
                    String shortTitle = ep.get("shortTitle");
                    String epTitle = ep.get("title");
                    String label = !shortTitle.isEmpty() ? shortTitle : epTitle;
                    if (label.isEmpty()) label = "第" + (i + 1) + "话";
                    sb.append("<div class='sidebar-item' data-epid='").append(escapeHtml(epIdStr)).append("' onclick='readEp(this)'>").append(escapeHtml(label)).append("</div>");
                }
                sb.append("</div>");
            }

            sb.append("</div>");

            sb.append("<script>");
            sb.append("function goBack(){try{window.parent.postMessage({type:'browser_go_back'},'*');}catch(e){try{history.back();}catch(e2){}}}");
            sb.append("function readEp(el){");
            sb.append("var epId=el.getAttribute('data-epid');");
            sb.append("var items=document.querySelectorAll('.ep-card,.sidebar-item');");
            sb.append("for(var i=0;i<items.length;i++){items[i].classList.remove('active');}");
            sb.append("document.querySelectorAll('[data-epid=\"'+epId+'\"]').forEach(function(e){e.classList.add('active');});");
            sb.append("document.getElementById('viewDetail').style.display='none';");
            sb.append("var reader=document.getElementById('viewReader');");
            sb.append("reader.style.display='flex';");
            sb.append("reader.innerHTML='<div class=\"loading\"><div class=\"spinner\"></div></div>';");
            sb.append("fetch('/api/browser/manga-images?ep_id='+encodeURIComponent(epId))");
            sb.append(".then(function(r){return r.json();})");
            sb.append(".then(function(data){");
            sb.append("if(data.images&&data.images.length>0){");
            sb.append("reader.innerHTML='';");
            sb.append("data.images.forEach(function(src){");
            sb.append("var img=document.createElement('img');img.src=src;img.loading='lazy';reader.appendChild(img);");
            sb.append("});");
            sb.append("reader.scrollTop=0;");
            sb.append("}else{reader.innerHTML='<div class=\"empty\"><p>无法加载漫画图片</p><p style=\"font-size:12px;color:#555\">'+data.error+'</p></div>';}");
            sb.append("}).catch(function(e){reader.innerHTML='<div class=\"empty\"><p>加载失败</p></div>';});");
            sb.append("}");
            sb.append("window.addEventListener('message',function(e){if(e.data&&e.data.type==='browser_pause_media'){try{document.querySelectorAll('video').forEach(function(v){if(!v.paused)v.pause();});document.querySelectorAll('audio').forEach(function(a){if(!a.paused)a.pause();});document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage({type:'browser_pause_media'},'*');}catch(ex){}});}catch(ex){}}});");
            sb.append("</script></body></html>");
            return sb.toString();
        } catch (Exception e) {
            return buildBangumiErrorPage(e.getMessage() != null ? e.getMessage() : "漫画加载失败");
        }
    }

    private String injectBilibiliMatchPage(String url) {
        try {
            String matchId = "";
            java.util.regex.Matcher matchMatcher = java.util.regex.Pattern.compile("/match/(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(url);
            if (matchMatcher.find()) {
                matchId = matchMatcher.group(1);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
            sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
            sb.append("<title>哔哩哔哩赛事</title>");
            sb.append("<style>");
            sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
            sb.append("html,body{width:100%;height:100%;overflow:hidden;background:#1a1a1a;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#fff;}");
            sb.append(".container{display:flex;flex-direction:column;width:100%;height:100%;}");
            sb.append(".header{padding:12px 16px;background:#222;display:flex;align-items:center;gap:12px;flex-shrink:0;}");
            sb.append(".back-btn{background:none;border:1px solid #444;color:#fff;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:13px;}");
            sb.append(".back-btn:hover{background:#333;}");
            sb.append(".header-title{font-size:16px;font-weight:600;flex:1;}");
            sb.append(".iframe-wrap{flex:1;position:relative;}");
            sb.append(".iframe-wrap iframe{width:100%;height:100%;border:none;}");
            sb.append(".loading{display:flex;align-items:center;justify-content:center;height:100%;color:#666;font-size:14px;flex-direction:column;gap:12px;}");
            sb.append(".spinner{width:36px;height:36px;border:3px solid rgba(255,255,255,0.2);border-top-color:#00a1d6;border-radius:50%;animation:spin .8s linear infinite;}");
            sb.append("@keyframes spin{to{transform:rotate(360deg)}}");
            sb.append("</style></head><body>");

            sb.append("<div class='container'>");
            sb.append("<div class='header'>");
            sb.append("<button class='back-btn' onclick='goBack()'>← 返回</button>");
            sb.append("<div class='header-title'>哔哩哔哩赛事</div>");
            sb.append("</div>");
            sb.append("<div class='iframe-wrap'>");
            sb.append("<div class='loading' id='loading'><div class='spinner'></div><p>加载中...</p></div>");
            sb.append("<iframe id='matchFrame' style='display:none' allow='autoplay;fullscreen;encrypted-media;picture-in-picture' allowfullscreen onload='this.style.display=\"block\";document.getElementById(\"loading\").style.display=\"none\"'></iframe>");
            sb.append("</div>");
            sb.append("</div>");

            sb.append("<script>");
            sb.append("function goBack(){try{window.parent.postMessage({type:'browser_go_back'},'*');}catch(e){try{history.back();}catch(e2){}}}");

            if (!matchId.isEmpty()) {
                sb.append("document.getElementById('matchFrame').src='/api/browser/proxy?url='+encodeURIComponent('https://www.bilibili.com/match/'+encodeURIComponent('").append(escapeJs(matchId)).append("'));");
            } else {
                sb.append("document.getElementById('matchFrame').src='/api/browser/proxy?url='+encodeURIComponent('https://www.bilibili.com/v/game');");

                try {
                    HttpRequest matchListReq = HttpRequest.newBuilder()
                            .uri(URI.create("https://api.bilibili.com/x/esports/gamelist/area/list"))
                            .timeout(Duration.ofSeconds(20))
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "https://www.bilibili.com/")
                            .header("Accept", "application/json,*/*")
                            .GET()
                            .build();
                    HttpResponse<byte[]> matchResp = httpClient.send(matchListReq, HttpResponse.BodyHandlers.ofByteArray());
                    String matchJson = new String(matchResp.body(), StandardCharsets.UTF_8);

                    if (matchJson.contains("\"code\":0") || matchJson.contains("\"code\": 0")) {
                        java.util.List<java.util.Map<String, String>> matchItems = new java.util.ArrayList<>();
                        java.util.regex.Matcher gameMatcher = java.util.regex.Pattern.compile(
                                "\"game_id\"\\s*:\\s*\"([^\"]+)\".*?\"title\"\\s*:\\s*\"([^\"]+)\""
                        ).matcher(matchJson);
                        while (gameMatcher.find() && matchItems.size() < 20) {
                            java.util.Map<String, String> item = new java.util.LinkedHashMap<>();
                            item.put("id", gameMatcher.group(1));
                            item.put("title", unescapeJson(gameMatcher.group(2)));
                            matchItems.add(item);
                        }

                        if (!matchItems.isEmpty()) {
                            sb = new StringBuilder();
                            sb.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
                            sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
                            sb.append("<title>哔哩哔哩赛事</title>");
                            sb.append("<style>");
                            sb.append("*{margin:0;padding:0;box-sizing:border-box;}");
                            sb.append("html,body{width:100%;height:100%;overflow:hidden;background:#1a1a1a;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;color:#fff;}");
                            sb.append(".container{display:flex;flex-direction:column;width:100%;height:100%;}");
                            sb.append(".header{padding:12px 16px;background:#222;display:flex;align-items:center;gap:12px;flex-shrink:0;}");
                            sb.append(".back-btn{background:none;border:1px solid #444;color:#fff;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:13px;}");
                            sb.append(".back-btn:hover{background:#333;}");
                            sb.append(".header-title{font-size:16px;font-weight:600;flex:1;}");
                            sb.append(".content{flex:1;overflow-y:auto;padding:16px;}");
                            sb.append(".match-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px;}");
                            sb.append(".match-card{background:#2a2a2a;border-radius:8px;padding:16px;cursor:pointer;transition:background .15s;}");
                            sb.append(".match-card:hover{background:#333;}");
                            sb.append(".match-card .name{font-size:14px;font-weight:500;}");
                            sb.append("</style></head><body>");
                            sb.append("<div class='container'>");
                            sb.append("<div class='header'>");
                            sb.append("<button class='back-btn' onclick='goBack()'>← 返回</button>");
                            sb.append("<div class='header-title'>哔哩哔哩赛事</div>");
                            sb.append("</div>");
                            sb.append("<div class='content'>");
                            sb.append("<div class='match-grid'>");
                            for (java.util.Map<String, String> item : matchItems) {
                                sb.append("<div class='match-card' onclick='openMatch(\"").append(escapeJs(item.get("id"))).append("\")'>");
                                sb.append("<div class='name'>").append(escapeHtml(item.get("title"))).append("</div>");
                                sb.append("</div>");
                            }
                            sb.append("</div></div></div>");
                            sb.append("<script>");
                            sb.append("function goBack(){try{window.parent.postMessage({type:'browser_go_back'},'*');}catch(e){try{history.back();}catch(e2){}}}");
                            sb.append("function openMatch(id){try{window.parent.postMessage({type:'browser_navigate',url:'https://www.bilibili.com/match/'+id},'*');}catch(e){}}");
                            sb.append("</script></body></html>");
                            return sb.toString();
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch match list: {}", e.getMessage());
                }
            }

            sb.append("</script></body></html>");
            return sb.toString();
        } catch (Exception e) {
            return buildBangumiErrorPage(e.getMessage() != null ? e.getMessage() : "赛事加载失败");
        }
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

    private boolean isByteDanceRelatedUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String lower = host.toLowerCase();
            return lower.endsWith(".zijieapi.com")
                    || lower.endsWith(".bytedance.com")
                    || lower.endsWith(".bytedance.net")
                    || lower.endsWith(".bytedance.cn")
                    || lower.endsWith(".bytegoofy.com")
                    || lower.endsWith(".ibytedtos.com")
                    || lower.endsWith(".toutiao.com")
                    || lower.endsWith(".douyin.com")
                    || lower.endsWith(".volces.com")
                    || lower.endsWith(".volcengine.com")
                    || lower.endsWith(".volcengineapi.com")
                    || lower.endsWith(".ivolces.com")
                    || lower.endsWith(".bytescm.com")
                    || lower.endsWith(".bytetos.com")
                    || lower.endsWith(".byteimg.com")
                    || lower.endsWith(".byted-static.com")
                    || lower.endsWith(".bdurl.net")
                    || lower.endsWith(".bytefe.com")
                    || lower.endsWith(".feilian.cn")
                    || lower.endsWith(".feilian.com")
                    || lower.endsWith(".doubao.com")
                    || lower.endsWith(".bytegecko.com")
                    || lower.endsWith(".bytedanceapi.com")
                    || lower.endsWith(".bytedanceapi.net")
                    || lower.endsWith(".snssdk.com")
                    || lower.endsWith(".pstatp.com")
                    || lower.endsWith(".bytecdn.cn")
                    || lower.endsWith(".bytedancevod.com");
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

    private boolean isBingTrackingUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains("glinkpingpost")
                || lower.contains("bing.com/fd/ls/")
                || lower.contains("bing.com/fd/s/")
                || lower.contains("/gl.ashx")
                || lower.contains("bing.com/api/spellcheck/")
                || lower.contains("bing.com/notifications/");
    }

    private boolean isByteDanceTrackingUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        return lower.contains("ibytedapm.com")
                || lower.contains("log.bytedance.com")
                || lower.contains("slardar.")
                || lower.contains("rangers.");
    }

    private boolean isLocalhostOrPrivateIp(String host) {
        if (host == null || host.isEmpty()) return false;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("0.0.0.0")
                || lower.equals("::1") || lower.endsWith(".local")) {
            return true;
        }
        if (lower.startsWith("127.") || lower.startsWith("10.")
                || lower.startsWith("192.168.") || lower.startsWith("169.254.")) {
            return true;
        }
        if (lower.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")) {
            return true;
        }
        return false;
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

    private boolean isIqiyiUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String lower = host.toLowerCase();
            return lower.endsWith(".iqiyi.com")
                    || lower.endsWith(".iq.com")
                    || lower.endsWith(".qiyi.com")
                    || lower.endsWith(".qiyipic.com")
                    || lower.endsWith(".71edge.com")
                    || lower.endsWith(".71.am");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isYinghuaUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String lower = host.toLowerCase();
            return lower.endsWith(".yinghuajinju.com")
                    || lower.endsWith(".yinghuadm.com")
                    || lower.endsWith(".imomoe.la")
                    || lower.endsWith(".imomoe.io")
                    || lower.endsWith(".yh5dm.cc")
                    || lower.endsWith(".jocy.tv")
                    || lower.endsWith(".605-zy.com")
                    || lower.endsWith(".cdn605.com")
                    || lower.endsWith(".bazhuayu.com")
                    || lower.endsWith(".sdplay.com")
                    || lower.endsWith(".baofeng10.com")
                    || lower.endsWith(".baofeng.com")
                    || lower.endsWith(".fengbao9.com")
                    || lower.endsWith(".fengbao.com");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isYinghuaVideoPageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String lowerHost = host.toLowerCase();
            if (!lowerHost.endsWith(".yinghuajinju.com")
                    && !lowerHost.endsWith(".yinghuadm.com")
                    && !lowerHost.endsWith(".imomoe.la")
                    && !lowerHost.endsWith(".imomoe.io")) return false;
            String path = URI.create(url).getPath().toLowerCase();
            return path.contains("/v/") || path.contains("/play") || path.contains("/view") || path.contains("/video");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIqiyiVideoDataApi(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String lowerHost = host.toLowerCase();
            // 视频播放信息API，返回的流URL含签名token不能被重写
            if (lowerHost.equals("pcw-data.video.iqiyi.com")) return true;
            if (lowerHost.contains("access.video.iqiyi.com")) return true;
            if (lowerHost.contains("cache.video.iqiyi.com")) return true;
            if (lowerHost.contains("iface.iqiyi.com")) return true;
            // 视频调度API
            String lower = url.toLowerCase();
            if (lower.contains("/videos/") && lowerHost.endsWith(".iqiyi.com")) return true;
            if (lower.contains("/player/") && lowerHost.endsWith(".iqiyi.com")) return true;
            if (lower.contains("/dispatch/") && lowerHost.endsWith(".iqiyi.com")) return true;
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private boolean isIqiyiVideoPageUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            String lowerHost = host.toLowerCase();
            if (!lowerHost.equals("www.iqiyi.com") && !lowerHost.equals("iqiyi.com")) return false;
            String path = URI.create(url).getPath().toLowerCase();
            return path.startsWith("/v_") || path.startsWith("/a_") || path.contains("/play");
        } catch (Exception e) {
            return false;
        }
    }

    private String injectIqiyiPlayerHelper(String html) {
        String helperScript = "<script>(function(){"
                + "console.log('[IqiyiHelper] v2 injecting...');"
                // 1. 从代理URL中提取原始URL
                + "var _iqiyiOrigUrl='';"
                + "try{"
                + "var _pu=window.location.href;"
                + "var _u=new URL(_pu);"
                + "_iqiyiOrigUrl=_u.searchParams.get('url')||'';"
                + "if(_iqiyiOrigUrl){_iqiyiOrigUrl=decodeURIComponent(_iqiyiOrigUrl);}"
                + "}catch(e){}"
                + "if(!_iqiyiOrigUrl){_iqiyiOrigUrl=window.location.href;}"
                + "var _oUrl;"
                + "try{_oUrl=new URL(_iqiyiOrigUrl);}catch(e){_oUrl=new URL('https://www.iqiyi.com/');}"
                + "var _oHostname=_oUrl.hostname;"
                + "var _oOrigin=_oUrl.origin;"
                // 2. 创建虚拟location对象
                + "var _fakeLoc={};"
                + "Object.defineProperties(_fakeLoc,{"
                + "'href':{get:function(){return _iqiyiOrigUrl;},set:function(){},enumerable:true}," 
                + "'origin':{get:function(){return _oOrigin;},enumerable:true},"
                + "'hostname':{get:function(){return _oHostname;},enumerable:true},"
                + "'protocol':{get:function(){return _oUrl.protocol;},enumerable:true},"
                + "'host':{get:function(){return _oUrl.host;},enumerable:true},"
                + "'pathname':{get:function(){return _oUrl.pathname;},enumerable:true},"
                + "'search':{get:function(){return _oUrl.search;},enumerable:true},"
                + "'hash':{get:function(){return _oUrl.hash;},enumerable:true},"
                + "'port':{get:function(){return _oUrl.port;},enumerable:true},"
                + "'ancestorOrigins':{value:{length:0,contains:function(){return false;},item:function(){return null;}}},"
                + "'assign':{value:function(){}},"
                + "'replace':{value:function(){}},"
                + "'reload':{value:function(){}},"
                + "'toString':{value:function(){return _iqiyiOrigUrl;}}"
                + "});"
                // 3. 强制覆盖window.location和document.location
                + "try{delete window.location;}catch(e){}"
                + "try{Object.defineProperty(window,'location',{get:function(){return _fakeLoc;},set:function(){},configurable:true});}catch(e){console.log('[IqiyiHelper] window.location override failed:',e);}"
                + "try{Object.defineProperty(document,'location',{get:function(){return _fakeLoc;},set:function(){},configurable:true});}catch(e){}"
                // 4. 覆盖document.referrer
                + "try{Object.defineProperty(document,'referrer',{get:function(){return _oOrigin+'/';},configurable:true});}catch(e){}"
                // 5. 覆盖document.URL和document.documentURI
                + "try{Object.defineProperty(document,'URL',{get:function(){return _iqiyiOrigUrl;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(document,'documentURI',{get:function(){return _iqiyiOrigUrl;},configurable:true});}catch(e){}"
                // 6. 禁用iframe检测
                + "try{"
                + "Object.defineProperty(window,'self',{get:function(){return window;},configurable:true});"
                + "Object.defineProperty(window,'top',{get:function(){return window;},configurable:true});"
                + "Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});"
                + "Object.defineProperty(window,'frameElement',{get:function(){return null;},configurable:true});"
                + "}catch(e){}"
                // 7. 覆盖document.domain
                + "try{"
                + "Object.defineProperty(document,'domain',{get:function(){return _oHostname;},set:function(v){},configurable:true});"
                + "}catch(e){}"
                // 8. 覆盖postMessage
                + "try{"
                + "var _origPostMessage=window.postMessage.bind(window);"
                + "window.postMessage=function(msg,origin){"
                + "if(typeof origin==='string'&&origin.indexOf('iqiyi.com')!==-1){origin='*';}"
                + "_origPostMessage(msg,origin);"
                + "};"
                + "}catch(e){}"
                // 9. 确保MediaSource可用
                + "try{"
                + "if(!window.MediaSource&&window.WebKitMediaSource){window.MediaSource=window.WebKitMediaSource;}"
                + "}catch(e){}"
                // 10. 覆盖Location.prototype属性（每个属性独立try-catch）
                + "var _locProps=['hostname','origin','protocol','host','pathname','search','hash','port','href'];"
                + "for(var i=0;i<_locProps.length;i++){"
                + "(function(prop){"
                + "try{"
                + "var _desc=Object.getOwnPropertyDescriptor(Location.prototype,prop)||Object.getOwnPropertyDescriptor(HTMLHyperlinkElementUtils.prototype,prop);"
                + "if(_desc&&_desc.get){"
                + "var _origGetter=_desc.get;"
                + "Object.defineProperty(Location.prototype,prop,{"
                + "get:function(){try{var v=_fakeLoc[prop];if(v!==undefined)return v;}catch(e){}return _origGetter.call(this);},"
                + "set:_desc.set,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "})(_locProps[i]);"
                + "}"
                // 11. 覆盖navigator属性以模拟正常浏览器环境
                + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'languages',{get:function(){return['zh-CN','zh','en'];},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'platform',{get:function(){return'Win32';},configurable:true});}catch(e){}"
                // 12. 处理浏览器自动播放策略——先静音播放，再恢复音量
                + "try{"
                + "var _origPlay=HTMLMediaElement.prototype.play;"
                + "HTMLMediaElement.prototype.play=function(){"
                + "var _el=this;"
                + "var _wasMuted=_el.muted;"
                + "if(!_el._iqiyiAutoPlayTried){"
                + "_el._iqiyiAutoPlayTried=true;"
                + "_el.muted=true;"
                + "}else{"
                + "_el.muted=false;"
                + "}"
                + "var _result=_origPlay.call(_el);"
                + "if(_result&&typeof _result.catch==='function'){"
                + "_result=_result.catch(function(e){"
                + "console.log('[IqiyiHelper] play() rejected ('+e.name+'), retrying muted...');"
                + "try{_el.muted=true;}catch(e2){}"
                + "return _origPlay.call(_el);"
                + "});"
                + "}"
                + "return _result;"
                + "};"
                + "}catch(e){}"
                // 13. 监听用户首次交互后恢复音量
                + "try{"
                + "var _restoreVol=function(){"
                + "var _vs=document.querySelectorAll('video');"
                + "for(var _vi=0;_vi<_vs.length;_vi++){"
                + "if(_vs[_vi]._iqiyiAutoPlayTried&&_vs[_vi].muted){"
                + "_vs[_vi].muted=false;"
                + "console.log('[IqiyiHelper] unmuted after user interaction');"
                + "}"
                + "}"
                + "};"
                + "document.addEventListener('click',_restoreVol,{once:true});"
                + "document.addEventListener('touchstart',_restoreVol,{once:true});"
                + "document.addEventListener('keydown',_restoreVol,{once:true});"
                + "}catch(e){}"
                + "console.log('[IqiyiHelper] v2 injected, origUrl='+_iqiyiOrigUrl+', hostname='+_oHostname);"
                + "})();</script>";
        if (html.contains("</head>")) {
            html = html.replace("</head>", helperScript + "</head>");
        } else if (html.contains("</HEAD>")) {
            html = html.replace("</HEAD>", helperScript + "</HEAD>");
        } else {
            html = helperScript + html;
        }
        return html;
    }

    private boolean isVideoStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host != null) {
                String lowerHost = host.toLowerCase();
                if (lowerHost.endsWith(".71edge.com") || lowerHost.endsWith(".71.am")) return true;
                if (lowerHost.endsWith(".snssdk.com") || lowerHost.endsWith(".bytedancevod.com")
                        || lowerHost.endsWith(".douyin.com")) return true;
                // 爱奇艺视频CDN域名
                if (lowerHost.endsWith(".data.video.iqiyi.com")) return true;
                if (lowerHost.endsWith(".dl.video.iqiyi.com")) return true;
                if (lowerHost.endsWith(".f10.video.iqiyi.com")) return true;
                if (lowerHost.endsWith(".c1.iqiyi.com")) return true;
                if (lowerHost.endsWith(".c2.iqiyi.com")) return true;
                if (lowerHost.equals("pcw-data.video.iqiyi.com")) return false;
                // 樱花动漫视频CDN域名
                if (lowerHost.endsWith(".605-zy.com")) return true;
                if (lowerHost.endsWith(".cdn605.com")) return true;
                if (lowerHost.endsWith(".bazhuayu.com")) return true;
                if (lowerHost.endsWith(".sdplay.com")) return true;
                if (lowerHost.endsWith(".baofeng10.com")) return true;
                if (lowerHost.endsWith(".baofeng.com")) return true;
                if (lowerHost.endsWith(".fengbao9.com")) return true;
                if (lowerHost.endsWith(".fengbao.com")) return true;
            }
        } catch (Exception ignored) {}
        String lower = url.toLowerCase();
        return lower.contains(".mp4") || lower.contains(".m4s") || lower.contains(".flv")
                || lower.contains(".m3u8") || lower.contains(".ts")
                || lower.contains("bilivideo.com") || lower.contains("acgvideo.com")
                || lower.contains("/playurl");
    }

    private String injectYinghuaPlayerHelper(String html) {
        String helperScript = "<script>(function(){"
                + "console.log('[YinghuaHelper] injecting...');"
                // 1. 从代理URL中提取原始URL
                + "var _yhOrigUrl='';"
                + "try{"
                + "var _pu=window.location.href;"
                + "var _u=new URL(_pu);"
                + "_yhOrigUrl=_u.searchParams.get('url')||'';"
                + "if(_yhOrigUrl){_yhOrigUrl=decodeURIComponent(_yhOrigUrl);}"
                + "}catch(e){}"
                + "if(!_yhOrigUrl){_yhOrigUrl=window.location.href;}"
                + "var _oUrl;"
                + "try{_oUrl=new URL(_yhOrigUrl);}catch(e){_oUrl=new URL('http://www.yinghuajinju.com/');}"
                + "var _oHostname=_oUrl.hostname;"
                + "var _oOrigin=_oUrl.origin;"
                // 2. URL代理函数：将原始URL转为代理URL
                + "var _toProxy=function(u){"
                + "if(!u||typeof u!=='string')return u;"
                + "if(u.indexOf('/api/browser/')===0)return u;"
                + "if(u.indexOf('blob:')===0||u.indexOf('data:')===0||u.indexOf('javascript:')===0)return u;"
                + "try{"
                + "var absUrl=new URL(u,_yhOrigUrl).toString();"
                + "return '/api/browser/proxy?url='+encodeURIComponent(absUrl);"
                + "}catch(e){return u;}"
                + "};"
                // 3. 拦截fetch请求
                + "try{"
                + "var _origFetch=window.fetch;"
                + "window.fetch=function(input,init){"
                + "var url=input;"
                + "if(input instanceof Request){url=input.url;}"
                + "if(typeof url==='string'&&url.length>0){"
                + "var proxied=_toProxy(url);"
                + "if(proxied!==url){"
                + "console.log('[YinghuaHelper] fetch proxy: '+url+' -> '+proxied);"
                + "if(input instanceof Request){"
                + "init=init||{};"
                + "init.method=input.method;"
                + "init.headers=input.headers;"
                + "init.body=input.body;"
                + "init.mode=input.mode;"
                + "init.credentials=input.credentials;"
                + "init.cache=input.cache;"
                + "init.redirect=input.redirect;"
                + "init.referrer=input.referrer;"
                + "init.integrity=input.integrity;"
                + "input=proxied;"
                + "}else{input=proxied;}"
                + "}"
                + "}"
                + "return _origFetch.call(window,input,init);"
                + "};"
                + "}catch(e){console.error('[YinghuaHelper] fetch intercept failed',e);}"
                // 4. 拦截XMLHttpRequest
                + "try{"
                + "var _origOpen=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(method,url,async,user,pass){"
                + "if(typeof url==='string'&&url.length>0){"
                + "var proxied=_toProxy(url);"
                + "if(proxied!==url){"
                + "console.log('[YinghuaHelper] XHR proxy: '+url+' -> '+proxied);"
                + "url=proxied;"
                + "}"
                + "}"
                + "return _origOpen.call(this,method,url,async!==false,user,pass);"
                + "};"
                + "}catch(e){console.error('[YinghuaHelper] XHR intercept failed',e);}"
                // 5. 拦截video/source的src属性设置
                + "try{"
                + "var _origSrcDesc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src')||{};"
                + "if(_origSrcDesc.set){"
                + "Object.defineProperty(HTMLMediaElement.prototype,'src',{"
                + "set:function(v){"
                + "var proxied=_toProxy(v);"
                + "if(proxied!==v){console.log('[YinghuaHelper] media.src proxy: '+v+' -> '+proxied);}"
                + "return _origSrcDesc.set.call(this,proxied);"
                + "},"
                + "get:_origSrcDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){console.error('[YinghuaHelper] media.src intercept failed',e);}"
                // 6. 拦截source标签的src属性
                + "try{"
                + "var _origSetAttr=Element.prototype.setAttribute;"
                + "Element.prototype.setAttribute=function(name,value){"
                + "if((name==='src'||name==='data-src')&&typeof value==='string'&&value.length>0){"
                + "var tag=this.tagName&&this.tagName.toLowerCase();"
                + "if(tag==='source'||tag==='video'||tag==='audio'||tag==='iframe'){"
                + "var proxied=_toProxy(value);"
                + "if(proxied!==value){console.log('[YinghuaHelper] setAttribute('+name+') proxy: '+value+' -> '+proxied);value=proxied;}"
                + "}"
                + "}"
                + "return _origSetAttr.call(this,name,value);"
                + "};"
                + "}catch(e){console.error('[YinghuaHelper] setAttribute intercept failed',e);}"
                // 7. 创建虚拟location对象
                + "var _fakeLoc={};"
                + "Object.defineProperties(_fakeLoc,{"
                + "'href':{get:function(){return _yhOrigUrl;},set:function(){},enumerable:true},"
                + "'origin':{get:function(){return _oOrigin;},enumerable:true},"
                + "'hostname':{get:function(){return _oHostname;},enumerable:true},"
                + "'protocol':{get:function(){return _oUrl.protocol;},enumerable:true},"
                + "'host':{get:function(){return _oUrl.host;},enumerable:true},"
                + "'pathname':{get:function(){return _oUrl.pathname;},enumerable:true},"
                + "'search':{get:function(){return _oUrl.search;},enumerable:true},"
                + "'hash':{get:function(){return _oUrl.hash;},enumerable:true},"
                + "'port':{get:function(){return _oUrl.port;},enumerable:true},"
                + "'ancestorOrigins':{value:{length:0,contains:function(){return false;},item:function(){return null;}}},"
                + "'assign':{value:function(){}},"
                + "'replace':{value:function(){}},"
                + "'reload':{value:function(){}},"
                + "'toString':{value:function(){return _yhOrigUrl;}}"
                + "});"
                // 8. 强制覆盖window.location和document.location
                + "try{delete window.location;}catch(e){}"
                + "try{Object.defineProperty(window,'location',{get:function(){return _fakeLoc;},set:function(){},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(document,'location',{get:function(){return _fakeLoc;},set:function(){},configurable:true});}catch(e){}"
                // 9. 覆盖document.referrer
                + "try{Object.defineProperty(document,'referrer',{get:function(){return _oOrigin+'/';},configurable:true});}catch(e){}"
                // 10. 覆盖document.URL和document.documentURI
                + "try{Object.defineProperty(document,'URL',{get:function(){return _yhOrigUrl;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(document,'documentURI',{get:function(){return _yhOrigUrl;},configurable:true});}catch(e){}"
                // 11. 禁用iframe检测
                + "try{"
                + "Object.defineProperty(window,'self',{get:function(){return window;},configurable:true});"
                + "Object.defineProperty(window,'top',{get:function(){return window;},configurable:true});"
                + "Object.defineProperty(window,'parent',{get:function(){return window;},configurable:true});"
                + "Object.defineProperty(window,'frameElement',{get:function(){return null;},configurable:true});"
                + "}catch(e){}"
                // 12. 覆盖document.domain
                + "try{"
                + "Object.defineProperty(document,'domain',{get:function(){return _oHostname;},set:function(v){},configurable:true});"
                + "}catch(e){}"
                // 13. 覆盖Location.prototype属性
                + "var _locProps=['hostname','origin','protocol','host','pathname','search','hash','port','href'];"
                + "for(var i=0;i<_locProps.length;i++){"
                + "(function(prop){"
                + "try{"
                + "var _desc=Object.getOwnPropertyDescriptor(Location.prototype,prop)||Object.getOwnPropertyDescriptor(HTMLHyperlinkElementUtils.prototype,prop);"
                + "if(_desc&&_desc.get){"
                + "var _origGetter=_desc.get;"
                + "Object.defineProperty(Location.prototype,prop,{"
                + "get:function(){try{var v=_fakeLoc[prop];if(v!==undefined)return v;}catch(e){}return _origGetter.call(this);},"
                + "set:_desc.set,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "})(_locProps[i]);"
                + "}"
                // 14. 覆盖navigator属性
                + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'languages',{get:function(){return['zh-CN','zh','en'];},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'platform',{get:function(){return'Win32';},configurable:true});}catch(e){}"
                // 15. 处理浏览器自动播放策略——先静音播放，再恢复音量
                + "try{"
                + "var _origPlay=HTMLMediaElement.prototype.play;"
                + "HTMLMediaElement.prototype.play=function(){"
                + "var _el=this;"
                + "if(!_el._yhAutoPlayTried){"
                + "_el._yhAutoPlayTried=true;"
                + "_el.muted=true;"
                + "}else{"
                + "_el.muted=false;"
                + "}"
                + "var _result=_origPlay.call(_el);"
                + "if(_result&&typeof _result.catch==='function'){"
                + "_result=_result.catch(function(e){"
                + "console.log('[YinghuaHelper] play() rejected ('+e.name+'), retrying muted...');"
                + "try{_el.muted=true;}catch(e2){}"
                + "return _origPlay.call(_el);"
                + "});"
                + "}"
                + "return _result;"
                + "};"
                + "}catch(e){}"
                // 16. 监听用户首次交互后恢复音量
                + "try{"
                + "var _restoreVol=function(){"
                + "var _vs=document.querySelectorAll('video');"
                + "for(var _vi=0;_vi<_vs.length;_vi++){"
                + "if(_vs[_vi]._yhAutoPlayTried&&_vs[_vi].muted){"
                + "_vs[_vi].muted=false;"
                + "console.log('[YinghuaHelper] unmuted after user interaction');"
                + "}"
                + "}"
                + "};"
                + "document.addEventListener('click',_restoreVol,{once:true});"
                + "document.addEventListener('touchstart',_restoreVol,{once:true});"
                + "document.addEventListener('keydown',_restoreVol,{once:true});"
                + "}catch(e){}"
                // 17. 确保iframe中的视频播放器能正常工作
                + "try{"
                + "var _origPostMessage=window.postMessage.bind(window);"
                + "window.postMessage=function(msg,origin){"
                + "if(typeof origin==='string'&&(origin.indexOf('yinghuajinju.com')!==-1||origin.indexOf('imomoe')!==-1||origin.indexOf('605-zy')!==-1)){origin='*';}"
                + "_origPostMessage(msg,origin);"
                + "};"
                + "}catch(e){}"
                // 18. 确保MediaSource可用
                + "try{"
                + "if(!window.MediaSource&&window.WebKitMediaSource){window.MediaSource=window.WebKitMediaSource;}"
                + "}catch(e){}"
                + "console.log('[YinghuaHelper] injected, origUrl='+_yhOrigUrl+', hostname='+_oHostname);"
                + "})();</script>";
        if (html.contains("</head>")) {
            html = html.replace("</head>", helperScript + "</head>");
        } else if (html.contains("</HEAD>")) {
            html = html.replace("</HEAD>", helperScript + "</HEAD>");
        } else {
            html = helperScript + html;
        }
        return html;
    }

    private String buildYinghuaCustomPlayer(String videoPageHtml, String videoPageUrl, String proxyBaseUrl) {
        try {
            String videoSrcUrl = null;

            // 模式A: data-vid属性（樱花动漫主要方式）
            // <div data-vid="https://v10.baofeng10.com/video/xxx/index.m3u8$mp4" id="playbox">
            java.util.regex.Matcher dataVidMatcher = java.util.regex.Pattern.compile(
                    "data-vid=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(videoPageHtml);
            if (dataVidMatcher.find()) {
                videoSrcUrl = dataVidMatcher.group(1).replaceAll("\\$mp4$", "");
                log.info("[YinghuaPlayer] Found video URL via data-vid: {}", videoSrcUrl);
            }

            // 模式B: changeplay('xxx') 函数调用
            if (videoSrcUrl == null) {
                java.util.regex.Matcher changeplayMatcher = java.util.regex.Pattern.compile(
                        "changeplay\\(['\"]([^'\"]+)['\"]\\)", java.util.regex.Pattern.CASE_INSENSITIVE
                ).matcher(videoPageHtml);
                if (changeplayMatcher.find()) {
                    videoSrcUrl = changeplayMatcher.group(1).replaceAll("\\$mp4$", "");
                    log.info("[YinghuaPlayer] Found video URL via changeplay: {}", videoSrcUrl);
                }
            }

            // 模式C: iframe URL（旧版页面可能使用）
            if (videoSrcUrl == null) {
                String iframeUrl = null;
                java.util.regex.Matcher iframeMatcher = java.util.regex.Pattern.compile(
                        "<iframe[^>]+src=[\"']([^\"']+)[\"'][^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE
                ).matcher(videoPageHtml);
                while (iframeMatcher.find()) {
                    String src = iframeMatcher.group(1);
                    if (src != null && !src.isEmpty() && !src.startsWith("#")) {
                        iframeUrl = src;
                        break;
                    }
                }
                if (iframeUrl != null) {
                    try {
                        iframeUrl = new URL(new URL(videoPageUrl), iframeUrl).toString();
                    } catch (Exception e) {
                        log.warn("[YinghuaPlayer] Failed to resolve iframe URL: {}", iframeUrl);
                        iframeUrl = null;
                    }
                }
                if (iframeUrl != null) {
                    log.info("[YinghuaPlayer] Found player iframe: {}", iframeUrl);
                    // 检查iframe URL的vid参数中是否直接包含m3u8/mp4 URL
                    try {
                        java.net.URI iframeUri = java.net.URI.create(iframeUrl);
                        String query = iframeUri.getQuery();
                        if (query != null && query.contains("vid=")) {
                            java.util.regex.Matcher vidMatcher = java.util.regex.Pattern.compile("vid=([^&]+)").matcher(query);
                            if (vidMatcher.find()) {
                                String vidValue = java.net.URLDecoder.decode(vidMatcher.group(1), "UTF-8");
                                if (vidValue.contains(".m3u8") || vidValue.contains(".mp4")) {
                                    videoSrcUrl = vidValue.replaceAll("\\$mp4$", "");
                                    log.info("[YinghuaPlayer] Found video URL from vid param: {}", videoSrcUrl);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.debug("[YinghuaPlayer] Failed to parse vid param: {}", e.getMessage());
                    }
                    // 如果vid参数中没有，请求iframe页面提取
                    if (videoSrcUrl == null) {
                        videoSrcUrl = extractVideoUrlFromIframe(iframeUrl, videoPageUrl);
                    }
                }
            }

            if (videoSrcUrl == null) {
                log.warn("[YinghuaPlayer] No video source URL found in video page: {}", videoPageUrl);
                return null;
            }

            log.info("[YinghuaPlayer] Final video source URL: {}", videoSrcUrl);

            // 构建视频源URL的代理URL
            String proxiedVideoUrl;
            if (videoSrcUrl.toLowerCase().contains(".m3u8")) {
                proxiedVideoUrl = "/api/browser/m3u8-proxy?url=" + encodeURIComponent(videoSrcUrl);
            } else {
                proxiedVideoUrl = "/api/browser/stream?url=" + encodeURIComponent(videoSrcUrl);
            }

            // 从原始页面提取标题
            String title = "樱花动漫";
            java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile(
                    "<title[^>]*>([^<]+)</title>", java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(videoPageHtml);
            if (titleMatcher.find()) {
                title = titleMatcher.group(1).trim();
            }

            // 从原始页面提取选集列表
            String episodeListHtml = extractEpisodeList(videoPageHtml, videoPageUrl);

            return buildCustomHlsPlayerHtml(title, proxiedVideoUrl, videoSrcUrl.toLowerCase().contains(".m3u8"), episodeListHtml);
        } catch (Exception e) {
            log.error("[YinghuaPlayer] Error building custom player: {}", e.getMessage(), e);
            return null;
        }
    }

    private String extractVideoUrlFromIframe(String iframeUrl, String refererUrl) {
        try {
            java.net.http.HttpRequest playerReq = java.net.http.HttpRequest.newBuilder()
                    .uri(safeCreateUri(iframeUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", refererUrl)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> playerResp = httpClient.send(playerReq, java.net.http.HttpResponse.BodyHandlers.ofString());
            String playerHtml = playerResp.body();
            log.info("[YinghuaPlayer] Player page fetched, status={}, bodyLen={}", playerResp.statusCode(), playerHtml.length());

            // 检查嵌套iframe
            java.util.regex.Matcher nestedIframeMatcher = java.util.regex.Pattern.compile(
                    "<iframe[^>]+src=[\"']([^\"']+)[\"'][^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(playerHtml);
            while (nestedIframeMatcher.find()) {
                String src = nestedIframeMatcher.group(1);
                if (src != null && !src.isEmpty()) {
                    try {
                        String nestedUrl = new URL(new URL(iframeUrl), src).toString();
                        log.info("[YinghuaPlayer] Found nested iframe: {}", nestedUrl);
                        java.net.http.HttpRequest nestedReq = java.net.http.HttpRequest.newBuilder()
                                .uri(safeCreateUri(nestedUrl))
                                .timeout(Duration.ofSeconds(30))
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                                .header("Referer", iframeUrl)
                                .GET()
                                .build();
                        java.net.http.HttpResponse<String> nestedResp = httpClient.send(nestedReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                        playerHtml = nestedResp.body();
                        iframeUrl = nestedUrl;
                        log.info("[YinghuaPlayer] Nested iframe fetched, bodyLen={}", playerHtml.length());
                    } catch (Exception e) {
                        log.debug("[YinghuaPlayer] Failed to fetch nested iframe: {}", e.getMessage());
                    }
                    break;
                }
            }

            // 从HTML中提取视频URL
            String[][] patterns = {
                    {"var pattern", "var\\s+\\w+\\s*=\\s*[\"']([^\"']*(?:\\.m3u8|\\.mp4)[^\"']*)[\"']"},
                    {"DPlayer config", "url\\s*:\\s*[\"']([^\"']*(?:\\.m3u8|\\.mp4)[^\"']*)[\"']"},
                    {"video/source tag", "<(?:video|source)[^>]+src=[\"']([^\"']+)[\"']"},
                    {"file config", "file\\s*:\\s*[\"']([^\"']*(?:\\.m3u8|\\.mp4|video|stream)[^\"']*)[\"']"},
                    {"m3u8 URL", "(https?://[^\"'\\s<>]+\\.m3u8[^\"'\\s<>]*)"},
                    {"mp4 URL", "(https?://[^\"'\\s<>]+\\.mp4[^\"'\\s<>]*)"},
            };
            for (String[] p : patterns) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(p[1], java.util.regex.Pattern.CASE_INSENSITIVE).matcher(playerHtml);
                if (m.find()) {
                    String url = m.group(1);
                    try {
                        url = new URL(new URL(iframeUrl), url).toString();
                    } catch (Exception e) { /* already absolute */ }
                    log.info("[YinghuaPlayer] Found video URL via {}: {}", p[0], url);
                    return url;
                }
            }
            log.warn("[YinghuaPlayer] No video URL found in iframe page. Preview: {}",
                    playerHtml.substring(0, Math.min(playerHtml.length(), 2000)));
        } catch (Exception e) {
            log.warn("[YinghuaPlayer] Error extracting video URL from iframe: {}", e.getMessage());
        }
        return null;
    }

    private String buildCustomHlsPlayerHtml(String title, String proxiedVideoUrl, boolean isHls, String episodeListHtml) {
        boolean hasEpisodes = episodeListHtml != null && !episodeListHtml.isEmpty();
        // 将 <a> 链接转换为 <div> 选集项（参考B站样式）
        String episodeItems = "";
        if (hasEpisodes) {
            // 解析选集链接，转换为div列表
            java.util.regex.Matcher aMatcher = java.util.regex.Pattern.compile(
                    "<a[^>]+data-url=['\"]([^'\"]+)['\"][^>]*>([^<]+)</a>", java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(episodeListHtml);
            StringBuilder items = new StringBuilder();
            while (aMatcher.find()) {
                String dataUrl = aMatcher.group(1);
                String text = aMatcher.group(2).trim();
                items.append("<div class='ep-item' data-url='").append(escapeHtml(dataUrl)).append("' onclick='switchEp(this)'>")
                     .append(escapeHtml(text)).append("</div>");
            }
            episodeItems = items.toString();
        }

        return "<!DOCTYPE html>\n"
                + "<html><head><meta charset='UTF-8'>\n"
                + "<meta name='viewport' content='width=device-width,initial-scale=1.0,maximum-scale=1.0,user-scalable=no'>\n"
                + "<title>" + escapeHtml(title) + "</title>\n"
                + "<style>\n"
                + ":root{--brand_pink:#FF6699;--bg1:#1a1a1a;--bg2:#222;--bg3:#2a2a2a;--text1:#eee;--text2:#aaa;--text3:#666;--line:#333;}\n"
                + "*{margin:0;padding:0;box-sizing:border-box;}\n"
                + "html,body{width:100%;height:100%;overflow:hidden;background:#000;color:#fff;font-family:-apple-system,BlinkMacSystemFont,Helvetica Neue,Helvetica,Arial,PingFang SC,Microsoft YaHei,sans-serif;}\n"
                // 顶部标题栏
                + ".header{display:flex;align-items:center;padding:10px 16px;background:#212121;gap:12px;height:44px;flex-shrink:0;}\n"
                + ".header h1{font-size:15px;font-weight:500;flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#eee;}\n"
                + ".toggle-ep{background:none;border:1px solid var(--brand_pink);color:var(--brand_pink);padding:4px 12px;border-radius:4px;cursor:pointer;font-size:13px;flex-shrink:0;}\n"
                + ".toggle-ep:hover{background:var(--brand_pink);color:#fff;}\n"
                // 主体布局：左侧视频 + 右侧选集
                + ".main{display:flex;height:calc(100vh - 44px);background:#000;}\n"
                + ".video-area{flex:1;display:flex;justify-content:center;align-items:center;background:#000;position:relative;min-width:0;}\n"
                + "video{max-width:100%;max-height:100%;width:100%;height:100%;object-fit:contain;}\n"
                // 右侧选集侧边栏（参考B站样式）
                + ".ep-sidebar{width:280px;background:var(--bg1);border-left:1px solid var(--line);flex-direction:column;flex-shrink:0;overflow:hidden;}\n"
                + ".ep-sidebar.hidden{display:none;}\n"
                + ".ep-sidebar.visible{display:flex;}\n"
                + ".ep-header{padding:12px 16px;font-size:14px;font-weight:500;color:#eee;border-bottom:1px solid var(--line);flex-shrink:0;display:flex;align-items:center;justify-content:space-between;}\n"
                + ".ep-count{font-size:12px;color:var(--text2);font-weight:400;}\n"
                + ".ep-list{flex:1;overflow-y:auto;padding:4px 0;}\n"
                + ".ep-list::-webkit-scrollbar{width:4px;}\n"
                + ".ep-list::-webkit-scrollbar-thumb{background:#444;border-radius:2px;}\n"
                + ".ep-item{padding:10px 16px;font-size:13px;color:var(--text2);cursor:pointer;transition:all .15s;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}\n"
                + ".ep-item:hover{background:var(--bg3);color:#eee;}\n"
                + ".ep-item.active{color:var(--brand_pink);background:#2a1a22;}\n"
                + ".ep-item.active::before{content:'';display:inline-block;width:3px;height:14px;background:var(--brand_pink);border-radius:2px;margin-right:8px;vertical-align:middle;}\n"
                // 加载和错误提示
                + ".loading-overlay{position:absolute;top:0;left:0;width:100%;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;background:rgba(0,0,0,.7);z-index:10;opacity:0;pointer-events:none;transition:opacity .2s;}\n"
                + ".loading-overlay.show{opacity:1;pointer-events:auto;}\n"
                + ".spinner{width:36px;height:36px;border:3px solid #333;border-top-color:var(--brand_pink);border-radius:50%;animation:spin .8s linear infinite;}\n"
                + "@keyframes spin{to{transform:rotate(360deg);}}\n"
                + ".error-msg{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#ff6b6b;font-size:14px;display:none;z-index:10;text-align:center;}\n"
                + ".retry-btn{margin-top:12px;padding:8px 24px;background:var(--brand_pink);color:#fff;border:none;border-radius:6px;cursor:pointer;font-size:14px;}\n"
                + ".retry-btn:hover{background:#e85689;}\n"
                // 移动端适配：窄屏时选集侧边栏变为底部抽屉
                + "@media(max-width:768px){\n"
                + ".ep-sidebar{position:fixed;bottom:0;left:0;right:0;width:100%;max-height:50vh;border-left:none;border-top:1px solid var(--line);border-radius:12px 12px 0 0;z-index:30;transform:translateY(100%);transition:transform .3s ease;}\n"
                + ".ep-sidebar.mobile-show{transform:translateY(0);}\n"
                + ".mobile-overlay{position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,.5);z-index:29;display:none;}\n"
                + ".mobile-overlay.visible{display:block;}\n"
                + "}\n"
                + "</style></head><body>\n"
                // 顶部标题栏
                + "<div class='header'>\n"
                + "<h1 id='videoTitle'>" + escapeHtml(title) + "</h1>\n"
                + (hasEpisodes ? "<button class='toggle-ep' id='toggleEpBtn' onclick='toggleEp()'>选集</button>" : "")
                + "</div>\n"
                // 主体
                + "<div class='main'>\n"
                + "<div class='video-area' id='videoArea'>\n"
                + "<video id='videoPlayer' controls autoplay playsinline></video>\n"
                + "<div class='loading-overlay show' id='loadingOverlay'><div class='spinner'></div></div>\n"
                + "<div id='errorMsg' class='error-msg'></div>\n"
                + "</div>\n"
                // 右侧选集侧边栏
                + (hasEpisodes ? "<div class='ep-sidebar visible' id='epSidebar'>\n"
                    + "<div class='ep-header'><span>选集</span><span class='ep-count' id='epCount'></span></div>\n"
                    + "<div class='ep-list' id='epList'>" + episodeItems + "</div>\n"
                    + "</div>\n"
                    + "<div class='mobile-overlay' id='mobileOverlay' onclick='closeMobileEp()'></div>" : "")
                + "</div>\n"
                + (isHls ? "<script src='https://cdn.jsdelivr.net/npm/hls.js@latest'></script>\n" : "")
                + "<script>\n"
                // 选集侧边栏控制
                + "var epVisible=true;\n"
                + "function toggleEp(){\n"
                + "var sb=document.getElementById('epSidebar');\n"
                + "var isMobile=window.innerWidth<=768;\n"
                + "if(isMobile){\n"
                + "sb.classList.toggle('mobile-show');\n"
                + "document.getElementById('mobileOverlay').classList.toggle('visible');\n"
                + "}else{\n"
                + "epVisible=!epVisible;\n"
                + "if(epVisible){sb.classList.remove('hidden');sb.classList.add('visible');}else{sb.classList.remove('visible');sb.classList.add('hidden');}\n"
                + "}\n"
                + "}\n"
                + "function closeMobileEp(){\n"
                + "document.getElementById('epSidebar').classList.remove('mobile-show');\n"
                + "document.getElementById('mobileOverlay').classList.remove('visible');\n"
                + "}\n"
                // 选集计数
                + "var epItems=document.querySelectorAll('.ep-item');\n"
                + "var epCountEl=document.getElementById('epCount');\n"
                + "if(epCountEl&&epItems.length>0)epCountEl.textContent='('+epItems.length+')';\n"
                // 视频播放器
                + "var video=document.getElementById('videoPlayer');\n"
                + "var loadingOverlay=document.getElementById('loadingOverlay');\n"
                + "var errorMsg=document.getElementById('errorMsg');\n"
                + "var currentSrc='" + escapeJs(proxiedVideoUrl) + "';\n"
                + "var isHls=" + isHls + ";\n"
                + "var hlsPlayer=null;\n"
                + "console.log('[Player] video src: '+currentSrc+', isHls: '+isHls);\n"
                + "function showLoading(show){if(loadingOverlay){if(show)loadingOverlay.classList.add('show');else loadingOverlay.classList.remove('show');}}\n"
                + "function showError(msg){showLoading(false);errorMsg.style.display='block';errorMsg.innerHTML=msg+'<br><button class=\"retry-btn\" onclick=\"location.reload()\">重试</button>';}\n"
                // 加载视频
                + "function loadVideo(src,hls){\n"
                + "showLoading(true);errorMsg.style.display='none';\n"
                + "if(hlsPlayer){hlsPlayer.destroy();hlsPlayer=null;}\n"
                + "currentSrc=src;isHls=hls;\n"
                + "console.log('[Player] loading: '+src);\n"
                + "if(hls&&typeof Hls!=='undefined'&&Hls.isSupported()){\n"
                + "hlsPlayer=new Hls({maxBufferLength:30,maxMaxBufferLength:60});\n"
                + "hlsPlayer.loadSource(src);hlsPlayer.attachMedia(video);\n"
                + "hlsPlayer.on(Hls.Events.MANIFEST_PARSED,function(){showLoading(false);video.play().catch(function(){});});\n"
                + "hlsPlayer.on(Hls.Events.ERROR,function(event,data){\n"
                + "console.error('[Player] HLS error:',data);\n"
                + "if(data.fatal){\n"
                + "switch(data.type){\n"
                + "case Hls.ErrorTypes.NETWORK_ERROR:showError('网络错误，正在重试...');hlsPlayer.startLoad();break;\n"
                + "case Hls.ErrorTypes.MEDIA_ERROR:showError('媒体错误');hlsPlayer.recoverMediaError();break;\n"
                + "default:showError('播放失败: '+data.details);break;\n"
                + "}\n"
                + "}\n"
                + "});\n"
                + "}else if(hls&&video.canPlayType('application/vnd.apple.mpegurl')){\n"
                + "video.src=src;video.addEventListener('loadedmetadata',function(){showLoading(false);video.play().catch(function(){});});\n"
                + "video.addEventListener('error',function(){showError('视频加载失败');});\n"
                + "}else{\n"
                + "video.src=src;\n"
                + "video.addEventListener('loadeddata',function(){showLoading(false);});\n"
                + "video.addEventListener('canplay',function(){showLoading(false);video.play().catch(function(){});});\n"
                + "video.addEventListener('error',function(){showError('视频加载失败');});\n"
                + "}\n"
                + "}\n"
                // 初始加载
                + "loadVideo(currentSrc,isHls);\n"
                // 选集切换（参考B站样式）
                + "function switchEp(el){\n"
                + "var url=el.getAttribute('data-url');\n"
                + "if(!url)return;\n"
                + "// 高亮当前选集\n"
                + "document.querySelectorAll('.ep-item').forEach(function(item){item.classList.remove('active');});\n"
                + "el.classList.add('active');\n"
                + "closeMobileEp();\n"
                + "// 通过代理获取新视频页面并提取视频源\n"
                + "fetch('/api/browser/yinghua-video-src?url='+encodeURIComponent(url)).then(function(r){return r.json();}).then(function(data){\n"
                + "if(data.success&&data.videoUrl){\n"
                + "var newSrc=data.videoUrl;\n"
                + "var newHls=newSrc.indexOf('.m3u8')>-1||newSrc.indexOf('m3u8-proxy')>-1;\n"
                + "document.getElementById('videoTitle').textContent=data.title||'樱花动漫';\n"
                + "document.title=data.title||'樱花动漫';\n"
                + "loadVideo(newSrc,newHls);\n"
                + "}else{showError(data.message||'获取视频源失败');}\n"
                + "}).catch(function(err){showError('加载失败: '+err.message);});\n"
                + "}\n"
                // 高亮当前集数
                + "(function(){\n"
                + "var links=document.querySelectorAll('.ep-item');\n"
                + "var currentOrigUrl='';\n"
                + "try{var pu=new URL(window.location.href);currentOrigUrl=pu.searchParams.get('url')||'';if(currentOrigUrl)currentOrigUrl=decodeURIComponent(currentOrigUrl);}catch(e){}\n"
                + "if(!currentOrigUrl)currentOrigUrl=window.location.href;\n"
                + "for(var i=0;i<links.length;i++){\n"
                + "var dataUrl=links[i].getAttribute('data-url')||'';\n"
                + "if(dataUrl&&(currentOrigUrl===dataUrl||currentOrigUrl.indexOf(dataUrl)>-1||dataUrl.indexOf(currentOrigUrl)>-1)){\n"
                + "links[i].classList.add('active');\n"
                + "links[i].scrollIntoView({block:'center'});\n"
                + "break;\n"
                + "}\n"
                + "}\n"
                + "})();\n"
                + "video.addEventListener('playing',function(){showLoading(false);});\n"
                + "</script>\n"
                + "</body></html>";
    }

    private String extractEpisodeList(String html, String currentPageUrl) {
        // 提取选集列表：樱花动漫页面的 <div class="movurls"> 下的所有 <a href="/v/xxx-xx.html"> 链接
        StringBuilder episodes = new StringBuilder();

        // 尝试多种匹配模式
        String[] movurlPatterns = {
                // 模式1: class="movurls" 或 class="movurl"
                "class=\"movurls?\"[^>]*>([\\s\\S]*?)</div>",
                // 模式2: class="movurls" 后面可能有多层嵌套div
                "class=\"movurls?\"[^>]*>([\\s\\S]*?)</ul>",
                // 模式3: 播放列表区域
                "播放列表[\\s\\S]*?<div[^>]*>([\\s\\S]*?)</ul>"
        };

        String movurlContent = null;
        for (int i = 0; i < movurlPatterns.length; i++) {
            java.util.regex.Matcher movurlMatcher = java.util.regex.Pattern.compile(
                    movurlPatterns[i], java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL
            ).matcher(html);
            if (movurlMatcher.find()) {
                movurlContent = movurlMatcher.group(1);
                log.info("[YinghuaPlayer] Episode list matched by pattern {}, contentLen={}", i + 1, movurlContent.length());
                break;
            }
        }

        if (movurlContent == null || movurlContent.isEmpty()) {
            log.info("[YinghuaPlayer] No episode list found in page: {}", currentPageUrl);
            return "";
        }

        // 提取所有选集链接
        java.util.regex.Matcher linkMatcher = java.util.regex.Pattern.compile(
                "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([^<]+)</a>", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(movurlContent);

        int count = 0;
        while (linkMatcher.find()) {
            String href = linkMatcher.group(1);
            String text = linkMatcher.group(2).trim();
            if (href == null || !href.contains("/v/")) continue; // 只保留视频页面链接
            try {
                String fullUrl = new URL(new URL(currentPageUrl), href).toString();
                // 构建选集链接：使用自定义播放器端点
                String playUrl = "/api/browser/proxy?url=" + encodeURIComponent(fullUrl);
                episodes.append("<a href='").append(escapeHtml(playUrl)).append("' data-url='").append(escapeHtml(fullUrl)).append("'>").append(escapeHtml(text)).append("</a>");
                count++;
            } catch (Exception e) {
                // skip
            }
        }

        log.info("[YinghuaPlayer] Extracted {} episodes from page: {}", count, currentPageUrl);
        return episodes.toString();
    }

    private String injectYinghuaRequestInterceptor(String html, String currentUrl) {
        // 为CDN域名的播放器页面注入轻量级请求拦截器
        // 这些页面中的JS会动态加载m3u8/ts URL，需要拦截并走代理
        String interceptorScript = "<script>(function(){"
                + "console.log('[YinghuaCDN] injecting request interceptor...');"
                // 获取当前页面的原始URL
                + "var _cdnOrigUrl='';"
                + "try{"
                + "var _pu=window.location.href;"
                + "var _u=new URL(_pu);"
                + "_cdnOrigUrl=_u.searchParams.get('url')||'';"
                + "if(_cdnOrigUrl){_cdnOrigUrl=decodeURIComponent(_cdnOrigUrl);}"
                + "}catch(e){}"
                + "if(!_cdnOrigUrl){_cdnOrigUrl=window.location.href;}"
                // URL代理函数
                + "var _cdnToProxy=function(u){"
                + "if(!u||typeof u!=='string')return u;"
                + "if(u.indexOf('/api/browser/')===0)return u;"
                + "if(u.indexOf('blob:')===0||u.indexOf('data:')===0||u.indexOf('javascript:')===0)return u;"
                + "try{"
                + "var absUrl=new URL(u,_cdnOrigUrl).toString();"
                + "return '/api/browser/proxy?url='+encodeURIComponent(absUrl);"
                + "}catch(e){return u;}"
                + "};"
                // 拦截fetch
                + "try{"
                + "var _origFetch=window.fetch;"
                + "window.fetch=function(input,init){"
                + "var url=input;"
                + "if(input instanceof Request){url=input.url;}"
                + "if(typeof url==='string'&&url.length>0){"
                + "var proxied=_cdnToProxy(url);"
                + "if(proxied!==url){"
                + "console.log('[YinghuaCDN] fetch: '+url);"
                + "if(input instanceof Request){input=proxied;}else{input=proxied;}"
                + "}"
                + "}"
                + "return _origFetch.call(window,input,init);"
                + "};"
                + "}catch(e){}"
                // 拦截XMLHttpRequest
                + "try{"
                + "var _origOpen=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(method,url,async,user,pass){"
                + "if(typeof url==='string'&&url.length>0){"
                + "var proxied=_cdnToProxy(url);"
                + "if(proxied!==url){console.log('[YinghuaCDN] XHR: '+url);url=proxied;}"
                + "}"
                + "return _origOpen.call(this,method,url,async!==false,user,pass);"
                + "};"
                + "}catch(e){}"
                // 拦截video/source的src属性
                + "try{"
                + "var _origSrcDesc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src')||{};"
                + "if(_origSrcDesc.set){"
                + "Object.defineProperty(HTMLMediaElement.prototype,'src',{"
                + "set:function(v){var proxied=_cdnToProxy(v);if(proxied!==v){console.log('[YinghuaCDN] media.src: '+v);}return _origSrcDesc.set.call(this,proxied);},"
                + "get:_origSrcDesc.get,configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                // 拦截setAttribute
                + "try{"
                + "var _origSetAttr=Element.prototype.setAttribute;"
                + "Element.prototype.setAttribute=function(name,value){"
                + "if((name==='src'||name==='data-src')&&typeof value==='string'&&value.length>0){"
                + "var tag=this.tagName&&this.tagName.toLowerCase();"
                + "if(tag==='source'||tag==='video'||tag==='audio'||tag==='iframe'){"
                + "var proxied=_cdnToProxy(value);"
                + "if(proxied!==value){console.log('[YinghuaCDN] setAttr('+name+'): '+value);value=proxied;}"
                + "}"
                + "}"
                + "return _origSetAttr.call(this,name,value);"
                + "};"
                + "}catch(e){}"
                // 拦截iframe src（播放器可能嵌套iframe）
                + "try{"
                + "var _origIframeSrcDesc=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'src')||{};"
                + "if(_origIframeSrcDesc.set){"
                + "Object.defineProperty(HTMLIFrameElement.prototype,'src',{"
                + "set:function(v){var proxied=_cdnToProxy(v);if(proxied!==v){console.log('[YinghuaCDN] iframe.src: '+v);}return _origIframeSrcDesc.set.call(this,proxied);},"
                + "get:_origIframeSrcDesc.get,configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "console.log('[YinghuaCDN] interceptor injected, origUrl='+_cdnOrigUrl);"
                + "})();</script>";
        if (html.contains("</head>")) {
            html = html.replace("</head>", interceptorScript + "</head>");
        } else if (html.contains("</HEAD>")) {
            html = html.replace("</HEAD>", interceptorScript + "</HEAD>");
        } else {
            html = interceptorScript + html;
        }
        return html;
    }

    private boolean isM3u8Content(String contentType, String url) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        if (lower.contains("mpegurl") || lower.contains("mp2t") || lower.contains("x-mpegurl")) {
            return true;
        }
        // 有些 CDN 返回 application/octet-stream，但 URL 以 .m3u8 结尾
        if (url != null && url.toLowerCase().contains(".m3u8")) {
            return true;
        }
        return false;
    }

    private String rewriteM3u8Urls(String m3u8, String m3u8Url, String proxyBaseUrl) {
        if (m3u8 == null || m3u8.isEmpty()) return m3u8;
        String[] lines = m3u8.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // #EXT-X-KEY 等标签中可能包含 URI="..." 需要重写
                if (trimmed.startsWith("#EXT-X-KEY") || trimmed.startsWith("#EXT-X-MEDIA")) {
                    line = rewriteM3u8TagUri(line, m3u8Url, proxyBaseUrl);
                }
                sb.append(line).append("\n");
            } else {
                // 这是一个 URL（.ts 分片、.m3u8 子播放列表等）
                String resolved;
                try {
                    resolved = new URL(new URL(m3u8Url), trimmed).toString();
                } catch (Exception e) {
                    resolved = trimmed;
                }
                String proxied = buildProxyUrl(resolved, proxyBaseUrl);
                sb.append(proxied).append("\n");
            }
        }
        return sb.toString();
    }

    private String rewriteM3u8TagUri(String tag, String m3u8Url, String proxyBaseUrl) {
        // 重写 #EXT-X-KEY:METHOD=AES-128,URI="https://xxx/key",IV=0x... 中的 URI
        java.util.regex.Matcher uriMatcher = java.util.regex.Pattern.compile("URI=\"([^\"]+)\"").matcher(tag);
        if (uriMatcher.find()) {
            String origUri = uriMatcher.group(1);
            String resolved;
            try {
                resolved = new URL(new URL(m3u8Url), origUri).toString();
            } catch (Exception e) {
                resolved = origUri;
            }
            String proxied = buildProxyUrl(resolved, proxyBaseUrl);
            tag = tag.replace("URI=\"" + origUri + "\"", "URI=\"" + proxied + "\"");
        }
        return tag;
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
        if (isDoubao) {
            return false;
        }
        return isBilibili || isBing || isQwen || isYuanbao;
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

    private String rewriteUploadUrls(String json, String proxyBaseUrl) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);

            if (root.has("Result")) {
                com.fasterxml.jackson.databind.node.ObjectNode result = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("Result");

                if (result.has("UploadAddress") && result.get("UploadAddress").isObject()) {
                    com.fasterxml.jackson.databind.node.ObjectNode uploadAddr = (com.fasterxml.jackson.databind.node.ObjectNode) result.get("UploadAddress");
                    if (uploadAddr.has("UploadHosts") && uploadAddr.get("UploadHosts").isArray()) {
                        com.fasterxml.jackson.databind.node.ArrayNode hosts = (com.fasterxml.jackson.databind.node.ArrayNode) uploadAddr.get("UploadHosts");
                        if (hosts.size() > 0) {
                            log.info("Upload host: {}", hosts.get(0).asText());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse upload URLs: {}", e.getMessage());
        }
        return json;
    }

    private String modifyHtml(String html, String originalUrl, String proxyBaseUrl) {
        String script = "<script>(function(){"
                + "var _proxyErrors=[];"
                + "window.addEventListener('error',function(e){_proxyErrors.push((e.message||e.type)+' at '+(e.filename||'')+'.'+(e.lineno||0));});"
                + "window.addEventListener('unhandledrejection',function(e){_proxyErrors.push('UnhandledRejection: '+(e.reason&&e.reason.message||e.reason||''));});"
                + "var _base='" + escapeJs(originalUrl) + "';"
                + "window.__isProxyPage=true;"
                + "window.addEventListener('beforeunload',function(e){e.stopImmediatePropagation();},true);"
                + "var _realTop=window.top;"
                + "try{Object.defineProperty(window,'parent',{get:function(){return window;}});}catch(e){}"
                + "try{Object.defineProperty(window,'top',{get:function(){return window;}});}catch(e){}"
                + "try{Object.defineProperty(window,'frameElement',{get:function(){return null;}});}catch(e){}"
                + "try{Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'languages',{get:function(){return['zh-CN','zh','en'];},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(navigator,'platform',{get:function(){return'Win32';},configurable:true});}catch(e){}"
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
                + "get href(){return _base;},set href(v){if(typeof v==='string'&&v.length>0){navigateTo(v);return;}_realLoc.replace(v);},"
                + "get origin(){return _bo;},"
                + "get hostname(){try{return new URL(_base).hostname;}catch(e){return '';}},"
                + "get protocol(){try{return new URL(_base).protocol;}catch(e){return 'https:';}},"
                + "get host(){try{return new URL(_base).host;}catch(e){return '';}},"
                + "get pathname(){try{return new URL(_base).pathname;}catch(e){return '/';}},"
                + "get search(){try{return new URL(_base).search;}catch(e){return '';}},"
                + "get hash(){try{return new URL(_base).hash;}catch(e){return '';}},"
                + "get port(){try{return new URL(_base).port;}catch(e){return '';}},"
                + "ancestorOrigins:(function(){var l={length:0,contains:function(){return false;},item:function(){return null;}};try{Object.defineProperty(l,'length',{value:0,writable:false});}catch(e){}return l;})(),"
                + "assign:function(u){navigateTo(u);},"
                + "replace:function(u){navigateTo(u);},"
                + "reload:function(){var now=Date.now();if(now-_lastReload<3000){return;}_lastReload=now;_realLoc.reload();},"
                + "toString:function(){return _base;}"
                + "};"
                + "try{Object.defineProperty(window,'location',{get:function(){return _fakeLoc;},configurable:true});}catch(e){}"
                + "try{Object.defineProperty(document,'location',{get:function(){return _fakeLoc;},configurable:true});}catch(e){}"
                + "try{"
                + "var _wpDesc=Object.getOwnPropertyDescriptor(Window.prototype,'location');"
                + "if(_wpDesc&&_wpDesc.configurable){"
                + "Object.defineProperty(Window.prototype,'location',{get:function(){return _fakeLoc;},configurable:true,enumerable:true});"
                + "}else{}"
                + "}catch(e){}"
                + "try{"
                + "var _dpDesc=Object.getOwnPropertyDescriptor(Document.prototype,'location');"
                + "if(_dpDesc&&_dpDesc.configurable){"
                + "Object.defineProperty(Document.prototype,'location',{get:function(){return _fakeLoc;},configurable:true,enumerable:true});"
                + "}else{}"
                + "}catch(e){}"
                + "try{"
                + "var _baseURL=new URL(_base);"
                + "var _targetPath=_baseURL.pathname+_baseURL.search+_baseURL.hash;"
                + "if(window.location.pathname!==_targetPath){"
                + "history.replaceState(null,'',_targetPath);"
                + "}"
                + "}catch(e){}"
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
                + "if(fakeVal!==undefined)return fakeVal;"
                + "}catch(e){}"
                + "return _origGetter.call(this);"
                + "},"
                + "set:prop==='href'&&_desc.set?function(v){if(typeof v==='string'&&v.length>0){navigateTo(v);return;}_desc.set.call(this,v);}:_desc.set,"
                + "configurable:true"
                + "});"
                + "}"
                + "})(_locProps[_pi]);"
                + "}"
                + "var _locAssign=Location.prototype.assign;"
                + "Location.prototype.assign=function(u){"
                + "try{"
                + "var resolved=new URL(u,_base).href;"
                + "navigateTo(resolved);"
                + "}catch(ex){_locAssign.call(this,proxyUrl(u));}"
                + "};"
                + "var _locReplace=Location.prototype.replace;"
                + "Location.prototype.replace=function(u){"
                + "try{"
                + "var resolved=new URL(u,_base).href;"
                + "navigateTo(resolved);"
                + "}catch(ex){_locReplace.call(this,proxyUrl(u));}"
                + "};"
                + "}catch(e){}"
                + "try{"
                + "var _ssrDataVal;"
                + "Object.defineProperty(window,'_SSR_DATA',{"
                + "set:function(v){"
                + "if(v&&typeof v==='object'){"
                + "if(v.renderLevel===2){v.renderLevel=1;}"
                + "}"
                + "_ssrDataVal=v;"
                + "},"
                + "get:function(){return _ssrDataVal;},"
                + "configurable:true"
                + "});"
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
                + "window.fetch=async function(input,init){"
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
                + "try{input=new Request(pu,input);}catch(e){"
                + "if(!init)init={};"
                + "var ni={};for(var k in init)ni[k]=init[k];"
                + "if(!ni.method)ni.method=input.method;"
                + "if(!ni.headers){try{ni.headers=Object.fromEntries(input.headers.entries());}catch(e2){}}"
                + "if(!ni.body){try{ni.body=await input.arrayBuffer();}catch(e3){try{ni.body=input.body;}catch(e4){}}}"
                + "if(!ni.credentials)ni.credentials='include';"
                + "input=pu;init=ni;"
                + "}"
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
                + "console.log('Blocked Bilibili danmaku WS:',resolved);"
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
                + "}catch(ex){}"
                + "}"
                + "try{"
                + "var wsUrlObj=new URL(resolved);"
                + "if(wsUrlObj.searchParams.has('referer')){"
                + "var ref=wsUrlObj.searchParams.get('referer');"
                + "if(ref&&ref.indexOf(_lo)===0){"
                + "wsUrlObj.searchParams.set('referer',_base);"
                + "resolved=wsUrlObj.href;"
                + "}"
                + "}"
                + "}catch(ex){}"
                + "var proxyWsUrl=_lo.replace(/^http/,'ws')+'/api/browser/ws-proxy?url='+encodeURIComponent(resolved);"
                + "if(protocols){"
                + "if(typeof protocols==='string'){proxyWsUrl+='&subprotocol='+encodeURIComponent(protocols);}"
                + "else if(Array.isArray(protocols)){proxyWsUrl+='&subprotocol='+encodeURIComponent(protocols.join(','));}"
                + "}"
                + "var ws=new _WebSocket(proxyWsUrl);"
                + "if(protocols){try{Object.defineProperty(ws,'protocol',{value:typeof protocols==='string'?protocols:protocols[0],configurable:true,writable:true});}catch(e){}}"
                + "return ws;"
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
                + "var _Worker=window.Worker;"
                + "if(_Worker){"
                + "window.Worker=function(u,opts){"
                + "console.log('[Proxy] Intercepting Worker: '+u);"
                + "try{"
                + "var absUrl=u;"
                + "try{absUrl=new URL(u,_base).href;}catch(ex){}"
                + "var wProxyCode=''"
                + "+'var _lo=\"'+_lo+'\";var _bo=\"'+_bo+'\";var _base=\"'+_base+'\";'"
                + "+'console.log(\"[Proxy] Worker proxy loaded, origin=\"+((typeof self!==\"undefined\"&&self.location)?self.location.href:\"unknown\"));'"
                + "+'function proxyUrl(u){'"
                + "+'if(u==null)return _lo+\"/api/browser/empty\";'"
                + "+'if(typeof u!==\"string\")u=String(u);'"
                + "+'if(!u||u===\"undefined\"||u===\"null\")return _lo+\"/api/browser/empty\";'"
                + "+'if(u.indexOf(\"/api/browser/\")!==-1)return u;'"
                + "+'if(u.indexOf(\"data:\")===0)return u;'"
                + "+'if(u.indexOf(\"blob:\")===0)return u;'"
                + "+'var r;try{r=new URL(u,_base).href;}catch(e){return u;}'"
                + "+'if(r.indexOf(_lo)===0&&r.indexOf(\"/api/browser/\")!==-1)return r;'"
                + "+'if(_bo&&_lo&&r.indexOf(_lo)===0){r=_bo+r.substring(_lo.length);}'"
                + "+'return _lo+\"/api/browser/proxy?url=\"+encodeURIComponent(r);'"
                + "+'}'"
                + "+'var _wxhrOpen=XMLHttpRequest.prototype.open;'"
                + "+'XMLHttpRequest.prototype.open=function(m,u,a,user,pass){'"
                + "+'return _wxhrOpen.call(this,m,proxyUrl(u),a!==false,user,pass);'"
                + "+'};'"
                + "+'var _wfetch=fetch;'"
                + "+'fetch=async function(input,init){'"
                + "+'var u;if(typeof input===\"string\"){u=input;}else if(input instanceof Request){u=input.url;}else if(input&&input.href){u=input.href;}else{u=String(input);}'"
                + "+'var pu=proxyUrl(u);'"
                + "+'if(pu!==u){'"
                + "+'if(!init)init={};'"
                + "+'if(!init.credentials)init.credentials=\"include\";'"
                + "+'if(typeof input===\"string\"){input=pu;}'"
                + "+'else if(input instanceof Request){'"
                + "+'try{input=new Request(pu,input);}catch(e){'"
                + "+'if(!init)init={};'"
                + "+'var ni={};for(var k in init)ni[k]=init[k];'"
                + "+'if(!ni.method)ni.method=input.method;'"
                + "+'if(!ni.headers){try{ni.headers=Object.fromEntries(input.headers.entries());}catch(e2){}}'"
                + "+'if(!ni.body){try{ni.body=await input.arrayBuffer();}catch(e3){try{ni.body=input.body;}catch(e4){}}}'"
                + "+'if(!ni.credentials)ni.credentials=\"include\";'"
                + "+'input=pu;init=ni;'"
                + "+'}'"
                + "+'}else{input=pu;}'"
                + "+'}'"
                + "+'return _wfetch.call(this,input,init);'"
                + "+'};'"
                + "+'importScripts(\"'+absUrl+'\");';"
                + "var wBlob=new Blob([wProxyCode],{type:'application/javascript'});"
                + "var wBlobUrl=URL.createObjectURL(wBlob);"
                + "return new _Worker(wBlobUrl,opts);"
                + "}catch(e){"
                + "return new _Worker(u,opts);"
                + "}"
                + "};"
                + "window.Worker.prototype=_Worker.prototype;"
                + "}"
                + "}catch(e){}"
                + "try{"
                + "var _SharedWorker=window.SharedWorker;"
                + "if(_SharedWorker){"
                + "window.SharedWorker=function(u,opts){"
                + "console.log('[Proxy] Intercepting SharedWorker: '+u);"
                + "try{"
                + "var absUrl=u;"
                + "try{absUrl=new URL(u,_base).href;}catch(ex){}"
                + "var swProxyCode=''"
                + "+'var _lo=\"'+_lo+'\";var _bo=\"'+_bo+'\";var _base=\"'+_base+'\";'"
                + "+'console.log(\"[Proxy] SharedWorker proxy loaded\");'"
                + "+'function proxyUrl(u){'"
                + "+'if(u==null)return _lo+\"/api/browser/empty\";'"
                + "+'if(typeof u!==\"string\")u=String(u);'"
                + "+'if(!u||u===\"undefined\"||u===\"null\")return _lo+\"/api/browser/empty\";'"
                + "+'if(u.indexOf(\"/api/browser/\")!==-1)return u;'"
                + "+'if(u.indexOf(\"data:\")===0)return u;'"
                + "+'if(u.indexOf(\"blob:\")===0)return u;'"
                + "+'var r;try{r=new URL(u,_base).href;}catch(e){return u;}'"
                + "+'if(r.indexOf(_lo)===0&&r.indexOf(\"/api/browser/\")!==-1)return r;'"
                + "+'if(_bo&&_lo&&r.indexOf(_lo)===0){r=_bo+r.substring(_lo.length);}'"
                + "+'return _lo+\"/api/browser/proxy?url=\"+encodeURIComponent(r);'"
                + "+'}'"
                + "+'var _wxhrOpen=XMLHttpRequest.prototype.open;'"
                + "+'XMLHttpRequest.prototype.open=function(m,u,a,user,pass){'"
                + "+'return _wxhrOpen.call(this,m,proxyUrl(u),a!==false,user,pass);'"
                + "+'};'"
                + "+'var _wfetch=fetch;'"
                + "+'fetch=async function(input,init){'"
                + "+'var u;if(typeof input===\"string\"){u=input;}else if(input instanceof Request){u=input.url;}else if(input&&input.href){u=input.href;}else{u=String(input);}'"
                + "+'var pu=proxyUrl(u);'"
                + "+'if(pu!==u){'"
                + "+'if(!init)init={};'"
                + "+'if(!init.credentials)init.credentials=\"include\";'"
                + "+'if(typeof input===\"string\"){input=pu;}'"
                + "+'else if(input instanceof Request){'"
                + "+'try{input=new Request(pu,input);}catch(e){'"
                + "+'if(!init)init={};'"
                + "+'var ni={};for(var k in init)ni[k]=init[k];'"
                + "+'if(!ni.method)ni.method=input.method;'"
                + "+'if(!ni.headers){try{ni.headers=Object.fromEntries(input.headers.entries());}catch(e2){}}'"
                + "+'if(!ni.body){try{ni.body=await input.arrayBuffer();}catch(e3){try{ni.body=input.body;}catch(e4){}}}'"
                + "+'if(!ni.credentials)ni.credentials=\"include\";'"
                + "+'input=pu;init=ni;'"
                + "+'}'"
                + "+'}else{input=pu;}'"
                + "+'}'"
                + "+'return _wfetch.call(this,input,init);'"
                + "+'};'"
                + "+'importScripts(\"'+absUrl+'\");';"
                + "var swBlob=new Blob([swProxyCode],{type:'application/javascript'});"
                + "var swBlobUrl=URL.createObjectURL(swBlob);"
                + "return new _SharedWorker(swBlobUrl,opts);"
                + "}catch(e){"
                + "return new _SharedWorker(u,opts);"
                + "}"
                + "};"
                + "window.SharedWorker.prototype=_SharedWorker.prototype;"
                + "}"
                + "}catch(e){}"
                + "try{"
                // 拦截form.action setter，捕获onsubmit动态设置的action值（必须在form.submit覆盖之前声明）
                + "var _rawFormActions=new WeakMap();"
                + "var _origActionDesc=Object.getOwnPropertyDescriptor(HTMLFormElement.prototype,'action');"
                + "if(_origActionDesc&&_origActionDesc.set){"
                + "Object.defineProperty(HTMLFormElement.prototype,'action',{"
                + "set:function(v){"
                // 只存储原始URL，不存储代理URL（避免setAttribute('action',proxyUrl)污染数据）
                + "if(typeof v==='string'&&v.indexOf('/api/browser/')===-1){_rawFormActions.set(this,v);}"
                + "_origActionDesc.set.call(this,v);"
                + "},"
                + "get:_origActionDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "try{"
                // 拦截 form.submit()：如果在_submitCapturing中则跳过（避免onsubmit调用submit()导致重复导航）
                + "var _formSubmit=HTMLFormElement.prototype.submit;"
                + "var _submitCapturing=false;"
                + "HTMLFormElement.prototype.submit=function(){"
                + "if(_submitCapturing)return;"
                + "try{"
                + "var f=this;"
                + "var u=_rawFormActions.get(f)||f.getAttribute('action')||_base;"
                + "if(u.indexOf('/api/browser/')!==-1){try{u=new URL(u,window.location.href).searchParams.get('url')||u;}catch(ex){}}"
                + "var resolved=new URL(u,_base).href;"
                + "f.setAttribute('action',proxyUrl(resolved));"
                + "}catch(e){}"
                + "_formSubmit.call(this);"
                + "};"
                + "}catch(e){}"
                + "try{"
                // 统一的表单提交拦截 - capture阶段，最先执行
                + "document.addEventListener('submit',function(e){"
                + "try{"
                + "var f=e.target;"
                + "if(!f||f.tagName!=='FORM')return;"
                + "e.preventDefault();"
                // 不调用stopImmediatePropagation，让B站等站点的submit事件处理器也能正常执行
                // 手动执行onsubmit，让它设置action（如樱花动漫的document.sform.action='/search/xxx/'）
                // 设置_submitCapturing标志，阻止onsubmit中的form.submit()调用导致重复导航
                + "var onsub=f.onsubmit;"
                + "if(typeof onsub==='function'){"
                + "_submitCapturing=true;"
                + "try{var r=onsub.call(f,e);if(r===false){_submitCapturing=false;return;}}catch(ex){_submitCapturing=false;throw ex;}"
                + "_submitCapturing=false;"
                + "}"
                // multipart表单：修改action为代理URL后提交
                + "var encType=f.getAttribute('enctype')||f.enctype||'';"
                + "if(encType.indexOf('multipart')!==-1||f.querySelector('input[type=file]')){"
                + "var u2=_rawFormActions.get(f)||f.getAttribute('action')||_base;"
                + "if(u2.indexOf('/api/browser/')!==-1){try{u2=new URL(u2,window.location.href).searchParams.get('url')||u2;}catch(ex){}}"
                + "var resolved2=new URL(u2,_base).href;"
                + "f.setAttribute('action',proxyUrl(resolved2));"
                + "_formSubmit.call(f);"
                + "return;"
                + "}"
                // 普通表单：收集参数后代理导航
                + "var method=(f.method||'GET').toUpperCase();"
                + "var fd=new FormData(f);"
                + "var params=new URLSearchParams(fd).toString();"
                // 读取action：优先_rawFormActions（onsubmit动态设置的原始值），再getAttribute（HTML属性）
                + "var u=_rawFormActions.get(f)||f.getAttribute('action')||'';"
                + "if(u.indexOf('/api/browser/')!==-1){try{u=new URL(u,window.location.href).searchParams.get('url')||u;}catch(ex){}}"
                + "if(u&&u.indexOf('javascript:')!==0){"
                + "try{u=new URL(u,_base).href;}catch(ex){}"
                + "}else{u=_base;}"
                + "if(method==='GET'&&params){"
                + "var sep=u.indexOf('?')===-1?'?':'&';"
                + "navigateTo(u+sep+params);"
                + "}else if(method==='POST'){"
                + "f.setAttribute('action',proxyUrl(u));"
                + "_formSubmit.call(f);"
                + "}else{"
                + "navigateTo(u);"
                + "}"
                + "}catch(ex){console.error('[Proxy] submit error:',ex);}"
                + "},true);"
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
                + "if(href.indexOf('javascript:')===0||href.indexOf('#')===0)return;"
                + "if(href.indexOf('/api/browser/')!==-1){try{href=new URL(href,window.location.href).searchParams.get('url')||href;}catch(e){}}"
                + "e.preventDefault();e.stopPropagation();"
                + "navigateTo(href);"
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
                + "if((tag==='IMG'||tag==='VIDEO'||tag==='AUDIO'||tag==='SOURCE'||tag==='IFRAME')&&n.src){"
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
                + "if(t==='SCRIPT'||t==='LINK'||t==='IMG'||t==='VIDEO'||t==='AUDIO'||t==='SOURCE'||t==='IFRAME'){"
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
                + "var _imgSrcDesc=Object.getOwnPropertyDescriptor(HTMLImageElement.prototype,'src');"
                + "if(_imgSrcDesc&&_imgSrcDesc.set){"
                + "var _origImgSrcSet=_imgSrcDesc.set;"
                + "Object.defineProperty(HTMLImageElement.prototype,'src',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origImgSrcSet.call(this,v);},"
                + "get:_imgSrcDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "try{"
                + "var _mediaSrcDesc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');"
                + "if(_mediaSrcDesc&&_mediaSrcDesc.set){"
                + "var _origMediaSrcSet=_mediaSrcDesc.set;"
                + "Object.defineProperty(HTMLMediaElement.prototype,'src',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origMediaSrcSet.call(this,v);},"
                + "get:_mediaSrcDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "try{"
                + "var _sourceSrcDesc=Object.getOwnPropertyDescriptor(HTMLSourceElement.prototype,'src');"
                + "if(_sourceSrcDesc&&_sourceSrcDesc.set){"
                + "var _origSourceSrcSet=_sourceSrcDesc.set;"
                + "Object.defineProperty(HTMLSourceElement.prototype,'src',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origSourceSrcSet.call(this,v);},"
                + "get:_sourceSrcDesc.get,"
                + "configurable:true"
                + "});"
                + "}"
                + "}catch(e){}"
                + "try{"
                + "var _iframeSrcDesc=Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype,'src');"
                + "if(_iframeSrcDesc&&_iframeSrcDesc.set){"
                + "var _origIframeSrcSet=_iframeSrcDesc.set;"
                + "Object.defineProperty(HTMLIFrameElement.prototype,'src',{"
                + "set:function(v){if(typeof v==='string'&&v.length>0){v=proxyUrl(v);}_origIframeSrcSet.call(this,v);},"
                + "get:_iframeSrcDesc.get,"
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
                + "if(now-_lastReload<3000){return;}"
                + "_lastReload=now;"
                + "_origLocReload.call(this);"
                + "};"
                + "new MutationObserver(function(ml){"
                + "for(var i=0;i<ml.length;i++){"
                + "var m=ml[i];"
                + "if(m.type==='childList'&&m.addedNodes){"
                + "for(var j=0;j<m.addedNodes.length;j++){"
                + "var n=m.addedNodes[j];"
                + "if(n.nodeType===1){proxyNodeAttrs(n);var sub=n.querySelectorAll&&n.querySelectorAll('img,video,audio,source,iframe,script,link');if(sub){for(var k=0;k<sub.length;k++){proxyNodeAttrs(sub[k]);}}}"
                + "}"
                + "}"
                + "}"
                + "}).observe(document.documentElement,{childList:true,subtree:true});"
                + "try{if(navigator.serviceWorker){"
                + "Object.defineProperty(navigator,'serviceWorker',{get:function(){return{register:function(){return Promise.reject(new Error('disabled'));},ready:Promise.resolve({unregister:function(){return Promise.resolve(true);}}),controller:null,addEventListener:function(){},removeEventListener:function(){}};},configurable:true});"
                + "}}catch(e){}"
                + "window.addEventListener('beforeunload',function(e){"
                + "try{var href=window.location.href;if(href.indexOf('/api/browser/')===-1){window.location.href=proxyUrl(href);}}catch(ex){}"
                + "},false);"
                + "window.addEventListener('message',function(e){"
                + "if(e.data&&e.data.type==='browser_pause_media'){"
                + "try{"
                + "document.querySelectorAll('video').forEach(function(v){if(!v.paused)v.pause();});"
                + "document.querySelectorAll('audio').forEach(function(a){if(!a.paused)a.pause();});"
                + "document.querySelectorAll('iframe').forEach(function(f){try{f.contentWindow.postMessage({type:'browser_pause_media'},'*');}catch(ex){}});"
                + "}catch(ex){}"
                + "}"
                + "},false);"
                + "window.addEventListener('DOMContentLoaded',function(){"
                + "setTimeout(function(){"
                + "if(_proxyErrors.length>0)console.warn('[Proxy] JS errors: '+_proxyErrors.join(' | '));"
                + "},5000);"
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

    private URI safeCreateUri(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            try {
                java.net.URL u = new java.net.URL(url);
                return new URI(u.getProtocol(), u.getAuthority(), u.getPath(), u.getQuery(), u.getRef());
            } catch (Exception ex) {
                String encoded = url
                        .replace("{", "%7B").replace("}", "%7D")
                        .replace("\"", "%22").replace("|", "%7C")
                        .replace("\\", "%5C").replace("^", "%5E")
                        .replace("`", "%60").replace("<", "%3C")
                        .replace(">", "%3E").replace("[", "%5B")
                        .replace("]", "%5D").replace(" ", "%20");
                return URI.create(encoded);
            }
        }
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
