package com.fongmi.android.tv.service;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.AiConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.TmdbConfig;
import com.fongmi.android.tv.bean.TmdbItem;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.helper.TmdbMatcher;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class AiRecommendationService {

    private static final int MAX_CONTEXT_ITEMS = 12;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int CONNECT_TIMEOUT_SECONDS = 8;
    private static final int READ_TIMEOUT_SECONDS = 15;
    private static final int CALL_TIMEOUT_SECONDS = 18;
    private static final int MAX_OUTPUT_TOKENS = 2048;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String[] KNOWN_COMPAT_SUFFIXES = {
            "/api/claudecode",
            "/api/anthropic",
            "/apps/anthropic",
            "/api/coding",
            "/claudecode",
            "/anthropic",
            "/step_plan",
            "/coding",
            "/claude"
    };

    private final TmdbService tmdbService;
    private final TmdbConfig tmdbConfig;
    private final TmdbMatcher tmdbMatcher;
    private final AiConfig config;

    public AiRecommendationService(TmdbService tmdbService, TmdbConfig tmdbConfig) {
        this.tmdbService = tmdbService == null ? new TmdbService() : tmdbService;
        this.tmdbConfig = tmdbConfig == null ? new TmdbConfig() : tmdbConfig;
        this.tmdbMatcher = new TmdbMatcher(this.tmdbService, this.tmdbConfig);
        this.config = AiConfig.objectFrom(Setting.getAiConfig());
    }

    private AiRecommendationService(TmdbService tmdbService, TmdbConfig tmdbConfig, AiConfig config) {
        this.tmdbService = tmdbService == null ? new TmdbService() : tmdbService;
        this.tmdbConfig = tmdbConfig == null ? new TmdbConfig() : tmdbConfig;
        this.tmdbMatcher = new TmdbMatcher(this.tmdbService, this.tmdbConfig);
        this.config = config == null ? new AiConfig().sanitize() : config.sanitize();
    }

    public PersonalRecommendationService.RecommendationPage load(@Nullable Vod currentVod, @Nullable String currentTitle, @Nullable String historyFingerprint, int pageSize) {
        String fingerprint = fingerprint(currentTitle, historyFingerprint, Setting.getKeyword(), config);
        if (!config.isReady()) return PersonalRecommendationService.RecommendationPage.empty(fingerprint);
        List<AiRecommendation> recommendations = readCache(fingerprint);
        if (recommendations.isEmpty()) {
            recommendations = requestRecommendations(currentVod, currentTitle);
            if (!recommendations.isEmpty()) writeCache(fingerprint, recommendations);
        }
        List<PersonalRecommendationService.RecommendationCandidate> candidates = new ArrayList<>();
        int order = 0;
        for (AiRecommendation recommendation : recommendations) {
            TmdbItem item = resolveItem(recommendation);
            if (item == null || isBlank(item.getTitle())) continue;
            String normalized = PersonalRecommendationService.normalizeTitle(item.getTitle());
            if (isBlank(normalized)) continue;
            candidates.add(new PersonalRecommendationService.RecommendationCandidate(item, aiKey(item, normalized), normalized, 100.0 - order, order));
            order++;
        }
        return pageAllCandidates(candidates, fingerprint);
    }

    public static TestResult testConfig(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isReady()) return TestResult.failed("请先启用智能推荐，并填写端点、API key 和模型。");
        AiRecommendationService service = new AiRecommendationService(null, null, safe);
        try {
            Request request = buildRequest(requestSpec(safe, "这是 AI 推荐配置连通性测试。请只返回 JSON: {\"items\":[{\"title\":\"流浪地球\",\"year\":2019,\"mediaType\":\"movie\",\"reason\":\"测试推荐\"}]}"));
            try (Response response = service.client().newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) return TestResult.failed("HTTP " + response.code() + ": " + excerpt(responseBody));
                List<AiRecommendation> recommendations = parseRecommendations(extractCompletionText(responseBody, safe));
                if (recommendations.isEmpty()) return TestResult.failed("接口已响应，但没有解析到推荐 JSON。");
                return TestResult.success(recommendations.size(), recommendations.get(0).title);
            }
        } catch (Throwable e) {
            return TestResult.failed(e.getMessage());
        }
    }

    public static ModelFetchResult fetchModels(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isModelFetchReady()) return ModelFetchResult.failed("请先填写端点和 API key。");
        AiRecommendationService service = new AiRecommendationService(null, null, safe);
        List<String> candidates = buildModelUrlCandidates(safe);
        if (candidates.isEmpty()) return ModelFetchResult.failed("无法从端点推导模型列表地址。");
        String lastError = "";
        for (String url : candidates) {
            try {
                Request.Builder builder = new Request.Builder().url(url).get();
                applyModelFetchHeaders(builder, safe);
                try (Response response = service.client().newCall(builder.build()).execute()) {
                    String responseBody = response.body() == null ? "" : response.body().string();
                    if (response.isSuccessful()) return ModelFetchResult.success(parseModelList(responseBody, safe));
                    lastError = "HTTP " + response.code() + ": " + excerpt(responseBody);
                    if (response.code() == 404 || response.code() == 405) continue;
                    return ModelFetchResult.failed(lastError);
                }
            } catch (Throwable e) {
                lastError = e.getMessage();
            }
        }
        return ModelFetchResult.failed(isBlank(lastError) ? "模型列表获取失败。" : lastError);
    }

    static String fingerprint(String currentTitle, String historyFingerprint, String searchRecords, AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        String value = "v2|"
                + Objects.toString(safe.getProtocol(), "") + "|"
                + Objects.toString(safe.getEndpoint(), "") + "|"
                + Objects.toString(safe.getModel(), "") + "|"
                + Objects.toString(safe.getCustomUserAgent(), "") + "|"
                + Objects.toString(safe.getRecommendPrompt(), "") + "|"
                + PersonalRecommendationService.normalizeTitle(currentTitle) + "|"
                + Objects.toString(historyFingerprint, "") + "|"
                + normalizeSearchRecords(searchRecords);
        return md5(value);
    }

    static String extractOutputText(String body) {
        if (isBlank(body)) return "";
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return "";
            JsonObject root = element.getAsJsonObject();
            String direct = string(root, "output_text");
            if (!isBlank(direct)) return direct;
            JsonArray output = array(root, "output");
            StringBuilder builder = new StringBuilder();
            for (JsonElement item : output) {
                if (!item.isJsonObject()) continue;
                JsonObject outputObject = item.getAsJsonObject();
                appendText(builder, outputObject);
                for (JsonElement content : array(outputObject, "content")) {
                    if (content.isJsonObject()) appendText(builder, content.getAsJsonObject());
                }
            }
            return builder.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    static String extractCompletionText(String body, AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        String text;
        switch (safe.getProtocol()) {
            case AiConfig.PROTOCOL_OPENAI_CHAT:
                text = extractOpenAiChatText(body);
                break;
            case AiConfig.PROTOCOL_ANTHROPIC_MESSAGES:
                text = extractAnthropicText(body);
                break;
            case AiConfig.PROTOCOL_GEMINI_NATIVE:
                text = extractGeminiText(body);
                break;
            case AiConfig.PROTOCOL_OPENAI_RESPONSES:
            default:
                text = extractOutputText(body);
                break;
        }
        if (!isBlank(text)) return text;
        text = extractOutputText(body);
        if (!isBlank(text)) return text;
        text = extractOpenAiChatText(body);
        if (!isBlank(text)) return text;
        text = extractAnthropicText(body);
        if (!isBlank(text)) return text;
        return extractGeminiText(body);
    }

    static RequestSpec requestSpec(AiConfig config, String prompt) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        JsonObject body = new JsonObject();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        switch (safe.getProtocol()) {
            case AiConfig.PROTOCOL_OPENAI_CHAT:
                body.addProperty("model", safe.getModel());
                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", Objects.toString(prompt, ""));
                messages.add(message);
                body.add("messages", messages);
                headers.put("Authorization", "Bearer " + safe.getApiKey());
                break;
            case AiConfig.PROTOCOL_ANTHROPIC_MESSAGES:
                body.addProperty("model", safe.getModel());
                body.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
                JsonArray anthropicMessages = new JsonArray();
                JsonObject anthropicMessage = new JsonObject();
                anthropicMessage.addProperty("role", "user");
                anthropicMessage.addProperty("content", Objects.toString(prompt, ""));
                anthropicMessages.add(anthropicMessage);
                body.add("messages", anthropicMessages);
                headers.put("x-api-key", safe.getApiKey());
                headers.put("anthropic-version", ANTHROPIC_VERSION);
                break;
            case AiConfig.PROTOCOL_GEMINI_NATIVE:
                JsonArray contents = new JsonArray();
                JsonObject content = new JsonObject();
                content.addProperty("role", "user");
                JsonArray parts = new JsonArray();
                JsonObject part = new JsonObject();
                part.addProperty("text", Objects.toString(prompt, ""));
                parts.add(part);
                content.add("parts", parts);
                contents.add(content);
                JsonObject generationConfig = new JsonObject();
                generationConfig.addProperty("maxOutputTokens", MAX_OUTPUT_TOKENS);
                body.add("contents", contents);
                body.add("generationConfig", generationConfig);
                headers.put("x-goog-api-key", safe.getApiKey());
                break;
            case AiConfig.PROTOCOL_OPENAI_RESPONSES:
            default:
                body.addProperty("model", safe.getModel());
                body.addProperty("input", Objects.toString(prompt, ""));
                headers.put("Authorization", "Bearer " + safe.getApiKey());
                break;
        }
        String userAgent = sanitizeUserAgent(safe.getCustomUserAgent());
        if (!isBlank(userAgent)) headers.put("User-Agent", userAgent);
        return new RequestSpec(buildCompletionUrl(safe), body, headers);
    }

    private static Request buildRequest(RequestSpec spec) {
        Request.Builder builder = new Request.Builder()
                .url(spec.url)
                .post(RequestBody.create(spec.body.toString(), JSON));
        for (Map.Entry<String, String> header : spec.headers.entrySet()) {
            if (!isBlank(header.getValue())) builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    static List<AiRecommendation> parseRecommendations(String text) {
        List<AiRecommendation> items = new ArrayList<>();
        String json = extractJson(text);
        if (isBlank(json)) return items;
        try {
            JsonElement element = JsonParser.parseString(json);
            JsonArray array;
            if (element.isJsonArray()) {
                array = element.getAsJsonArray();
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                array = object.has("items") && object.get("items").isJsonArray()
                        ? object.getAsJsonArray("items")
                        : object.has("recommendations") && object.get("recommendations").isJsonArray() ? object.getAsJsonArray("recommendations") : new JsonArray();
            } else {
                array = new JsonArray();
            }
            for (JsonElement item : array) {
                if (!item.isJsonObject()) continue;
                AiRecommendation recommendation = AiRecommendation.from(item.getAsJsonObject());
                if (recommendation != null) items.add(recommendation);
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        return items;
    }

    private List<AiRecommendation> requestRecommendations(Vod currentVod, String currentTitle) {
        try {
            Request request = buildRequest(requestSpec(config, buildPrompt(currentVod, currentTitle)));
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    SpiderDebug.log("ai-rec", "request failed code=%d", response.code());
                    return new ArrayList<>();
                }
                return parseRecommendations(extractCompletionText(response.body().string(), config));
            }
        } catch (Throwable e) {
            SpiderDebug.log("ai-rec", "request failed error=%s", e.getMessage());
            return new ArrayList<>();
        }
    }

    private OkHttpClient client() {
        return com.github.catvod.net.OkHttp.client().newBuilder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    private String buildPrompt(Vod currentVod, String currentTitle) {
        StringBuilder builder = new StringBuilder();
        builder.append(config.getRecommendPrompt()).append("\n\n");
        builder.append("当前影片: ").append(Objects.toString(currentTitle, "")).append('\n');
        builder.append("播放历史:\n");
        for (String title : historyTitles(currentVod)) builder.append("- ").append(title).append('\n');
        builder.append("搜索记录:\n");
        for (String keyword : searchKeywords()) builder.append("- ").append(keyword).append('\n');
        return builder.toString();
    }

    private List<String> historyTitles(Vod currentVod) {
        List<String> titles = new ArrayList<>();
        String current = currentVod == null ? "" : currentVod.getName();
        try {
            for (History history : History.get()) {
                if (history == null || isBlank(history.getVodName())) continue;
                if (PersonalRecommendationService.normalizeTitle(history.getVodName()).equals(PersonalRecommendationService.normalizeTitle(current))) continue;
                addUnique(titles, history.getVodName());
                if (titles.size() >= MAX_CONTEXT_ITEMS) break;
            }
        } catch (Throwable e) {
            SpiderDebug.log("ai-rec", "history read failed: %s", e.getMessage());
        }
        return titles;
    }

    private List<String> searchKeywords() {
        List<String> keywords = new ArrayList<>();
        try {
            JsonElement element = JsonParser.parseString(Setting.getKeyword());
            if (element != null && element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    if (!item.isJsonPrimitive()) continue;
                    addUnique(keywords, item.getAsString());
                    if (keywords.size() >= MAX_CONTEXT_ITEMS) break;
                }
            }
        } catch (Throwable ignored) {
        }
        return keywords;
    }

    private TmdbItem resolveItem(AiRecommendation recommendation) {
        if (recommendation == null || isBlank(recommendation.title)) return null;
        String mediaType = "tv".equals(recommendation.mediaType) ? "tv" : "movie";
        if (tmdbConfig.isReady()) {
            try {
                TmdbItem item = tmdbMatcher.searchAndMatch(recommendation.title);
                if (item != null) return withReason(item, recommendation.reason);
            } catch (Throwable e) {
                SpiderDebug.log("ai-rec", "tmdb resolve failed title=%s error=%s", recommendation.title, e.getMessage());
            }
        }
        try {
            TmdbItem item = new PersonalRecommendationService(tmdbService, tmdbConfig).matchDoubanItem(recommendation.title, mediaType, recommendation.year);
            if (item != null) return withReason(item, recommendation.reason);
        } catch (Throwable e) {
            SpiderDebug.log("ai-rec", "douban resolve failed title=%s error=%s", recommendation.title, e.getMessage());
        }
        String subtitle = recommendation.subtitle();
        return new TmdbItem(-Math.abs((recommendation.title + recommendation.year + mediaType).hashCode()), mediaType, recommendation.title, subtitle, recommendation.reason, "", "", "", 0.0);
    }

    static PersonalRecommendationService.RecommendationPage pageAllCandidates(List<PersonalRecommendationService.RecommendationCandidate> candidates, String fingerprint) {
        List<PersonalRecommendationService.RecommendationCandidate> ranked = PersonalRecommendationService.rankCandidates(candidates, Integer.MAX_VALUE);
        return PersonalRecommendationService.pageItems(ranked, 0, Math.max(1, ranked.size()), fingerprint, false);
    }

    private TmdbItem withReason(TmdbItem item, String reason) {
        if (item == null || isBlank(reason)) return item;
        return new TmdbItem(
                item.getTmdbId(),
                item.getMediaType(),
                item.getTitle(),
                item.getSubtitle(),
                reason,
                item.getPosterUrl(),
                item.getBackdropUrl(),
                item.getCredit(),
                item.getRating(),
                item.getOriginalLanguage(),
                item.getOriginCountry(),
                item.getGenreIds(),
                item.getDepartment()
        );
    }

    private List<AiRecommendation> readCache(String fingerprint) {
        try {
            File file = cacheFile(fingerprint);
            if (file == null || !file.exists() || file.length() <= 0) return new ArrayList<>();
            return parseRecommendations(Path.read(file));
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    private void writeCache(String fingerprint, List<AiRecommendation> recommendations) {
        try {
            File file = cacheFile(fingerprint);
            if (file == null || recommendations == null || recommendations.isEmpty()) return;
            JsonArray array = new JsonArray();
            for (AiRecommendation recommendation : recommendations) array.add(recommendation.toJson());
            JsonObject root = new JsonObject();
            root.add("items", array);
            Path.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private File cacheFile(String fingerprint) {
        try {
            File dir = new File(Path.cache(), "ai_rec");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, fingerprint + ".json");
        } catch (Throwable e) {
            return null;
        }
    }

    static List<String> buildModelUrlCandidates(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        String endpoint = cleanBaseEndpoint(safe.getEndpoint());
        if (AiConfig.PROTOCOL_GEMINI_NATIVE.equals(safe.getProtocol())) return buildGeminiModelUrlCandidates(endpoint);
        return buildOpenAiCompatibleModelUrlCandidates(endpoint, isKnownFullCompletionEndpoint(endpoint));
    }

    static List<ModelInfo> parseModelList(String body, AiConfig config) {
        List<ModelInfo> models = new ArrayList<>();
        if (isBlank(body)) return models;
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return models;
            JsonObject root = element.getAsJsonObject();
            for (JsonElement item : array(root, "data")) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                addModel(models, firstString(object, "id", "name"), firstString(object, "owned_by", "ownedBy", "owner"));
            }
            for (JsonElement item : array(root, "models")) {
                if (!item.isJsonObject()) continue;
                JsonObject object = item.getAsJsonObject();
                if (AiConfig.PROTOCOL_GEMINI_NATIVE.equals(safe.getProtocol()) && !supportsGeminiGenerateContent(object)) continue;
                String ownedBy = AiConfig.PROTOCOL_GEMINI_NATIVE.equals(safe.getProtocol()) ? "Google" : firstString(object, "owned_by", "ownedBy", "owner");
                addModel(models, stripGeminiModelPrefix(firstString(object, "id", "name", "baseModelId")), ownedBy);
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        Collections.sort(models, (left, right) -> left.id.compareToIgnoreCase(right.id));
        return models;
    }

    static String sanitizeUserAgent(String userAgent) {
        String value = Objects.toString(userAgent, "").trim();
        if (value.isEmpty()) return "";
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) return "";
        }
        return value;
    }

    private static String buildCompletionUrl(AiConfig config) {
        String endpoint = cleanBaseEndpoint(config.getEndpoint());
        switch (config.getProtocol()) {
            case AiConfig.PROTOCOL_OPENAI_CHAT:
                if (endpoint.endsWith("/chat/completions")) return endpoint;
                return appendCompletionPath(endpoint, "chat/completions");
            case AiConfig.PROTOCOL_ANTHROPIC_MESSAGES:
                if (endpoint.endsWith("/messages")) return endpoint;
                return appendCompletionPath(endpoint, "messages");
            case AiConfig.PROTOCOL_GEMINI_NATIVE:
                return buildGeminiGenerateContentUrl(endpoint, config.getModel());
            case AiConfig.PROTOCOL_OPENAI_RESPONSES:
            default:
                if (endpoint.endsWith("/responses")) return endpoint;
                return appendCompletionPath(endpoint, "responses");
        }
    }

    private static String appendCompletionPath(String endpoint, String path) {
        if (isBlank(endpoint)) return "";
        if (endsWithVersionSegment(endpoint)) return endpoint + "/" + path;
        return endpoint + "/v1/" + path;
    }

    private static String buildGeminiGenerateContentUrl(String endpoint, String model) {
        if (endpoint.contains(":generateContent")) return endpoint;
        String value = endpoint;
        String modelPath = geminiModelPath(model);
        if (value.endsWith("/models")) return value + "/" + stripGeminiModelPrefix(modelPath) + ":generateContent";
        if (!endsWithGeminiVersionSegment(value)) value = value + "/v1beta";
        return value + "/" + modelPath + ":generateContent";
    }

    private static List<String> buildOpenAiCompatibleModelUrlCandidates(String endpoint, boolean fullUrl) {
        List<String> candidates = new ArrayList<>();
        if (isBlank(endpoint)) return candidates;
        if (fullUrl) {
            int idx = endpoint.indexOf("/v1/");
            if (idx >= 0) candidates.add(endpoint.substring(0, idx) + "/v1/models");
            int lastSlash = endpoint.lastIndexOf('/');
            int schemeEnd = endpoint.indexOf("://") + 3;
            if (candidates.isEmpty() && lastSlash > schemeEnd) candidates.add(endpoint.substring(0, lastSlash) + "/v1/models");
            return unique(candidates);
        }
        if (endsWithVersionSegment(endpoint)) {
            candidates.add(endpoint + "/models");
            if (!endpoint.endsWith("/v1")) candidates.add(endpoint + "/v1/models");
        } else {
            candidates.add(endpoint + "/v1/models");
        }
        String stripped = stripCompatSuffix(endpoint);
        if (!isBlank(stripped) && stripped.contains("://")) {
            candidates.add(stripped + "/v1/models");
            candidates.add(stripped + "/models");
        }
        return unique(candidates);
    }

    private static List<String> buildGeminiModelUrlCandidates(String endpoint) {
        List<String> candidates = new ArrayList<>();
        if (isBlank(endpoint)) return candidates;
        if (endpoint.contains(":generateContent")) {
            int idx = endpoint.indexOf("/models/");
            if (idx >= 0) candidates.add(endpoint.substring(0, idx) + "/models");
        } else if (endpoint.endsWith("/models")) {
            candidates.add(endpoint);
        } else if (endsWithGeminiVersionSegment(endpoint)) {
            candidates.add(endpoint + "/models");
        } else {
            candidates.add(endpoint + "/v1beta/models");
        }
        return unique(candidates);
    }

    private static void applyModelFetchHeaders(Request.Builder builder, AiConfig config) {
        if (AiConfig.PROTOCOL_GEMINI_NATIVE.equals(config.getProtocol())) {
            builder.header("x-goog-api-key", config.getApiKey());
        } else {
            builder.header("Authorization", "Bearer " + config.getApiKey());
            if (AiConfig.PROTOCOL_ANTHROPIC_MESSAGES.equals(config.getProtocol())) {
                builder.header("x-api-key", config.getApiKey());
                builder.header("anthropic-version", ANTHROPIC_VERSION);
            }
        }
        String userAgent = sanitizeUserAgent(config.getCustomUserAgent());
        if (!isBlank(userAgent)) builder.header("User-Agent", userAgent);
    }

    private static String extractOpenAiChatText(String body) {
        if (isBlank(body)) return "";
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return "";
            JsonObject root = element.getAsJsonObject();
            StringBuilder builder = new StringBuilder();
            for (JsonElement choice : array(root, "choices")) {
                if (!choice.isJsonObject()) continue;
                JsonObject choiceObject = choice.getAsJsonObject();
                appendContent(builder, object(choiceObject, "message").get("content"));
                appendContent(builder, object(choiceObject, "delta").get("content"));
                appendTextValue(builder, string(choiceObject, "text"));
            }
            return builder.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String extractAnthropicText(String body) {
        if (isBlank(body)) return "";
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return "";
            JsonObject root = element.getAsJsonObject();
            StringBuilder builder = new StringBuilder();
            appendTextValue(builder, string(root, "completion"));
            for (JsonElement content : array(root, "content")) {
                if (content.isJsonObject()) appendText(builder, content.getAsJsonObject());
                else if (content.isJsonPrimitive()) appendTextValue(builder, content.getAsString());
            }
            return builder.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String extractGeminiText(String body) {
        if (isBlank(body)) return "";
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element == null || !element.isJsonObject()) return "";
            JsonObject root = element.getAsJsonObject();
            StringBuilder builder = new StringBuilder();
            appendTextValue(builder, string(root, "text"));
            for (JsonElement candidate : array(root, "candidates")) {
                if (!candidate.isJsonObject()) continue;
                JsonObject content = object(candidate.getAsJsonObject(), "content");
                for (JsonElement part : array(content, "parts")) {
                    if (part.isJsonObject()) appendText(builder, part.getAsJsonObject());
                    else if (part.isJsonPrimitive()) appendTextValue(builder, part.getAsString());
                }
            }
            return builder.toString().trim();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String normalizeSearchRecords(String searchRecords) {
        if (isBlank(searchRecords)) return "";
        try {
            JsonElement element = JsonParser.parseString(searchRecords);
            if (!element.isJsonArray()) return PersonalRecommendationService.normalizeTitle(searchRecords);
            List<String> values = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonPrimitive()) continue;
                String normalized = PersonalRecommendationService.normalizeTitle(item.getAsString());
                if (!isBlank(normalized) && !values.contains(normalized)) values.add(normalized);
            }
            return String.join("|", values);
        } catch (Throwable e) {
            return PersonalRecommendationService.normalizeTitle(searchRecords);
        }
    }

    private static String extractJson(String text) {
        String value = Objects.toString(text, "").trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*", "").replaceFirst("```$", "").trim();
        }
        if (value.startsWith("{") || value.startsWith("[")) return value;
        int objectStart = value.indexOf('{');
        int arrayStart = value.indexOf('[');
        if (objectStart < 0 && arrayStart < 0) return "";
        boolean useArray = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart);
        int start = useArray ? arrayStart : objectStart;
        int end = useArray ? value.lastIndexOf(']') : value.lastIndexOf('}');
        return end > start ? value.substring(start, end + 1) : "";
    }

    private static void appendText(StringBuilder builder, JsonObject object) {
        String text = string(object, "text");
        if (isBlank(text)) text = string(object, "output_text");
        appendTextValue(builder, text);
    }

    private static void appendContent(StringBuilder builder, JsonElement content) {
        if (content == null || content.isJsonNull()) return;
        if (content.isJsonPrimitive()) {
            appendTextValue(builder, content.getAsString());
            return;
        }
        if (content.isJsonObject()) {
            appendText(builder, content.getAsJsonObject());
            return;
        }
        if (!content.isJsonArray()) return;
        for (JsonElement item : content.getAsJsonArray()) {
            if (item.isJsonObject()) appendText(builder, item.getAsJsonObject());
            else if (item.isJsonPrimitive()) appendTextValue(builder, item.getAsString());
        }
    }

    private static void appendTextValue(StringBuilder builder, String text) {
        if (isBlank(text)) return;
        if (builder.length() > 0) builder.append('\n');
        builder.append(text.trim());
    }

    private static JsonObject object(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) return "";
        return Objects.toString(object.get(key).getAsString(), "").trim();
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            String value = string(object, key);
            if (!isBlank(value)) return value;
        }
        return "";
    }

    private static void addModel(List<ModelInfo> models, String id, String ownedBy) {
        String value = Objects.toString(id, "").trim();
        if (isBlank(value)) return;
        for (ModelInfo model : models) if (model.id.equals(value)) return;
        models.add(new ModelInfo(value, ownedBy));
    }

    private static boolean supportsGeminiGenerateContent(JsonObject object) {
        JsonArray methods = array(object, "supportedGenerationMethods");
        if (methods.size() == 0) return true;
        for (JsonElement method : methods) {
            if (method.isJsonPrimitive() && "generateContent".equals(method.getAsString())) return true;
        }
        return false;
    }

    private static String geminiModelPath(String model) {
        String value = Objects.toString(model, "").trim();
        if (value.startsWith("models/") || value.startsWith("publishers/")) return value;
        return "models/" + stripGeminiModelPrefix(value);
    }

    private static String stripGeminiModelPrefix(String model) {
        String value = Objects.toString(model, "").trim();
        return value.startsWith("models/") ? value.substring("models/".length()) : value;
    }

    private static String cleanBaseEndpoint(String endpoint) {
        String value = Objects.toString(endpoint, "").trim();
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int fragment = value.indexOf('#');
        if (fragment >= 0) value = value.substring(0, fragment);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static boolean isKnownFullCompletionEndpoint(String endpoint) {
        return endpoint.endsWith("/responses")
                || endpoint.endsWith("/chat/completions")
                || endpoint.endsWith("/messages")
                || endpoint.contains(":generateContent");
    }

    private static boolean endsWithVersionSegment(String url) {
        String last = url == null ? "" : url.substring(url.lastIndexOf('/') + 1);
        if (last.length() < 2 || last.charAt(0) != 'v') return false;
        for (int i = 1; i < last.length(); i++) if (!Character.isDigit(last.charAt(i))) return false;
        return true;
    }

    private static boolean endsWithGeminiVersionSegment(String url) {
        String last = url == null ? "" : url.substring(url.lastIndexOf('/') + 1);
        if (last.length() < 2 || last.charAt(0) != 'v') return false;
        int i = 1;
        while (i < last.length() && Character.isDigit(last.charAt(i))) i++;
        if (i == 1) return false;
        String suffix = last.substring(i);
        return suffix.isEmpty() || "beta".equals(suffix) || "alpha".equals(suffix);
    }

    private static String stripCompatSuffix(String endpoint) {
        for (String suffix : KNOWN_COMPAT_SUFFIXES) {
            if (endpoint.endsWith(suffix)) return endpoint.substring(0, endpoint.length() - suffix.length());
        }
        return "";
    }

    private static List<String> unique(List<String> values) {
        List<String> unique = new ArrayList<>();
        for (String value : values) {
            if (isBlank(value) || unique.contains(value)) continue;
            unique.add(value);
        }
        return unique;
    }

    private static void addUnique(List<String> values, String value) {
        if (isBlank(value)) return;
        String normalized = PersonalRecommendationService.normalizeTitle(value);
        for (String item : values) if (PersonalRecommendationService.normalizeTitle(item).equals(normalized)) return;
        values.add(value.trim());
    }

    private static String aiKey(TmdbItem item, String normalized) {
        return item.getTmdbId() > 0 ? item.getMediaType() + ":" + item.getTmdbId() : "ai:" + normalized;
    }

    private static boolean isBlank(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static String md5(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(Objects.toString(text, "").getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
            return builder.toString();
        } catch (Throwable e) {
            return Integer.toHexString(Objects.toString(text, "").hashCode());
        }
    }

    private static String excerpt(String text) {
        String value = Objects.toString(text, "").replace('\n', ' ').trim();
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    static final class RequestSpec {

        final String url;
        final JsonObject body;
        final Map<String, String> headers;

        private RequestSpec(String url, JsonObject body, Map<String, String> headers) {
            this.url = url == null ? "" : url;
            this.body = body == null ? new JsonObject() : body;
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }
    }

    public static final class ModelInfo {

        private final String id;
        private final String ownedBy;

        private ModelInfo(String id, String ownedBy) {
            this.id = id == null ? "" : id.trim();
            this.ownedBy = ownedBy == null ? "" : ownedBy.trim();
        }

        public String getId() {
            return id;
        }

        public String getOwnedBy() {
            return ownedBy;
        }
    }

    public static final class ModelFetchResult {

        private final boolean success;
        private final String message;
        private final List<ModelInfo> models;

        private ModelFetchResult(boolean success, String message, List<ModelInfo> models) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.models = models == null ? new ArrayList<>() : models;
        }

        static ModelFetchResult success(List<ModelInfo> models) {
            return new ModelFetchResult(true, "", models);
        }

        static ModelFetchResult failed(String message) {
            return new ModelFetchResult(false, message, new ArrayList<>());
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public List<ModelInfo> getModels() {
            return models;
        }
    }

    public static final class TestResult {

        private final boolean success;
        private final String message;
        private final int count;
        private final String sampleTitle;

        private TestResult(boolean success, String message, int count, String sampleTitle) {
            this.success = success;
            this.message = message == null ? "" : message;
            this.count = count;
            this.sampleTitle = sampleTitle == null ? "" : sampleTitle;
        }

        static TestResult success(int count, String sampleTitle) {
            return new TestResult(true, "", count, sampleTitle);
        }

        static TestResult failed(String message) {
            return new TestResult(false, message, 0, "");
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public int getCount() {
            return count;
        }

        public String getSampleTitle() {
            return sampleTitle;
        }
    }

    static final class AiRecommendation {

        final String title;
        final int year;
        final String mediaType;
        final String reason;

        private AiRecommendation(String title, int year, String mediaType, String reason) {
            this.title = title == null ? "" : title.trim();
            this.year = year;
            this.mediaType = "tv".equals(mediaType) ? "tv" : "movie";
            this.reason = reason == null ? "" : reason.trim();
        }

        static AiRecommendation from(JsonObject object) {
            String title = firstString(object, "title", "name", "vodName");
            if (isBlank(title)) return null;
            String mediaType = firstString(object, "mediaType", "type", "category");
            int year = firstInt(object, "year", "releaseYear");
            String reason = firstString(object, "reason", "desc", "overview");
            return new AiRecommendation(title, year, mediaType, reason);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("title", title);
            object.addProperty("year", year);
            object.addProperty("mediaType", mediaType);
            object.addProperty("reason", reason);
            return object;
        }

        String subtitle() {
            List<String> parts = new ArrayList<>();
            parts.add("tv".equals(mediaType) ? "剧集" : "电影");
            if (year > 0) parts.add(String.valueOf(year));
            if (!isBlank(reason)) parts.add(reason);
            return String.join(" · ", parts);
        }

        private static String firstString(JsonObject object, String... keys) {
            for (String key : keys) {
                String value = string(object, key);
                if (!isBlank(value)) return value;
            }
            return "";
        }

        private static int firstInt(JsonObject object, String... keys) {
            for (String key : keys) {
                try {
                    String value = string(object, key);
                    if (!isBlank(value)) return Integer.parseInt(value.replaceAll("[^0-9]", ""));
                } catch (Throwable ignored) {
                }
            }
            return 0;
        }
    }
}
