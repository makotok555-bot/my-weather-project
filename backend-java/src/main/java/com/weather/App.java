package com.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewaymanagementapi.ApiGatewayManagementApiClient;
import software.amazon.awssdk.services.apigatewaymanagementapi.model.PostToConnectionRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

public class App implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    private final String CONN_TABLE = "WebSocketConnections";
    private final String HISTORY_TABLE = "ChatHistory";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent input, Context context) {
        String routeKey = input.getRequestContext().getRouteKey();
        String connectionId = input.getRequestContext().getConnectionId();
        
        try (DynamoDbClient dbClient = DynamoDbClient.create()) {
            if ("$connect".equals(routeKey)) {
                saveConnection(dbClient, connectionId);
            } else if ("$disconnect".equals(routeKey)) {
                removeConnection(dbClient, connectionId);
            } else if ("gethistory".equals(routeKey)) {
                sendHistoryToRequester(input, context, dbClient);
            } else if ("sendmessage".equals(routeKey)) {
                processMessageAndBroadcast(input, context, dbClient);
            }
        } catch (Exception e) {
            context.getLogger().log("Error: " + e.getMessage());
        }
        return createResponse(200, "OK");
    }

    private void processMessageAndBroadcast(APIGatewayV2WebSocketEvent input, Context context, DynamoDbClient dbClient) {
        String displayName = "匿名";
        String content = "";
        try {
            JsonNode root = mapper.readTree(input.getBody());
            displayName = root.path("userName").asText("匿名");
            content = root.path("data").asText("");
        } catch (Exception e) {
            content = input.getBody();
        }

        // 1. 通常のメッセージを保存・配信
        String finalMessage = "[" + displayName + "] " + content;
        saveToHistory(dbClient, finalMessage);
        broadcastToAll(input, context, dbClient, finalMessage);

        // 2. ボットコマンド (!tenki 郵便番号) の判定
        if (content.startsWith("!tenki ")) {
            String zipCode = content.replace("!tenki ", "").trim();
            String weatherResult = fetchWeather(zipCode, context);
            String botMessage = "[お天気ボット] " + weatherResult;
            
            // ボットの発言も履歴に保存して全員に送る
            saveToHistory(dbClient, botMessage);
            broadcastToAll(input, context, dbClient, botMessage);
        }
    }

private String fetchWeather(String zipCode, Context context) {
        try {
            // 1. 郵便番号専用の検索メソッド(getLocation)に変更し、パラメータをpostalに変更
            // ハイフンがあってもなくても対応できるように postal パラメータを使用します
            // String geoUrl = "http://geoapi.heartrails.com/api/json?method=getLocation&postal=" + zipCode;
            String geoUrl = "http://geoapi.heartrails.com/api/json?method=searchByPostal&postal=" + zipCode;
            HttpRequest geoReq = HttpRequest.newBuilder().uri(URI.create(geoUrl)).build();
            String geoRes = httpClient.send(geoReq, HttpResponse.BodyHandlers.ofString()).body();
            
            // クラウド監視(CloudWatch)にAPIの生の返答を出すようにします
            context.getLogger().log("Geo API Response: " + geoRes);

            JsonNode geoRoot = mapper.readTree(geoRes);
            JsonNode locationArray = geoRoot.path("response").path("location");
            
            // データが空、またはノードが存在しない場合のチェック
            if (locationArray.isMissingNode() || locationArray.isEmpty()) {
                return "郵便番号[" + zipCode + "]の場所が見つかりませんでした。";
            }

            // 最初の候補を採用
            JsonNode geoNode = locationArray.get(0);
            String lat = geoNode.path("y").asText();
            String lon = geoNode.path("x").asText();
            String city = geoNode.path("prefecture").asText() + geoNode.path("city").asText() + geoNode.path("town").asText();

            // 2. 天気取得 (Open-Meteo)
            String weatherUrl = String.format("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s&current_weather=true", lat, lon);
            HttpRequest weatherReq = HttpRequest.newBuilder().uri(URI.create(weatherUrl)).build();
            String weatherRes = httpClient.send(weatherReq, HttpResponse.BodyHandlers.ofString()).body();
            
            JsonNode weatherNode = mapper.readTree(weatherRes).path("current_weather");
            if (weatherNode.isMissingNode()) {
                return city + "は見つかりましたが、天気が取得できませんでした。";
            }

            int weatherCode = weatherNode.path("weathercode").asInt();
            double temp = weatherNode.path("temperature").asDouble();

            // 天気アイコン付きで返却
            return city + "の天気は " + translateWeather(weatherCode) + "、気温は " + temp + "℃ です。";
            
        } catch (Exception e) {
            context.getLogger().log("Weather Bot Error: " + e.getMessage());
            return "ボットがエラーを起こしました: " + e.getMessage();
        }
    }

    private String translateWeather(int code) {
        if (code == 0) return "快晴 ☀️";
        if (code <= 3) return "晴れ/曇り ☁️";
        if (code <= 67) return "雨 ☔";
        if (code <= 77) return "雪 ❄️";
        return "不安定な天気";
    }

    private void saveToHistory(DynamoDbClient dbClient, String text) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("messageId", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
        item.put("timestamp", AttributeValue.builder().n(String.valueOf(Instant.now().getEpochSecond())).build());
        item.put("text", AttributeValue.builder().s(text).build());
        dbClient.putItem(PutItemRequest.builder().tableName(HISTORY_TABLE).item(item).build());
    }

    private void saveConnection(DynamoDbClient dbClient, String id) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("connectionId", AttributeValue.builder().s(id).build());
        dbClient.putItem(PutItemRequest.builder().tableName(CONN_TABLE).item(item).build());
    }

    private void removeConnection(DynamoDbClient dbClient, String id) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("connectionId", AttributeValue.builder().s(id).build());
        dbClient.deleteItem(DeleteItemRequest.builder().tableName(CONN_TABLE).key(key).build());
    }

    private void sendHistoryToRequester(APIGatewayV2WebSocketEvent input, Context context, DynamoDbClient dbClient) {
        String callbackUrl = getCallbackUrl(input);
        ScanResponse response = dbClient.scan(ScanRequest.builder().tableName(HISTORY_TABLE).build());
        List<Map<String, AttributeValue>> items = new ArrayList<>(response.items());
        items.sort(Comparator.comparing(m -> Long.parseLong(m.get("timestamp").n())));

        try (ApiGatewayManagementApiClient apiClient = ApiGatewayManagementApiClient.builder().endpointOverride(URI.create(callbackUrl)).build()) {
            for (Map<String, AttributeValue> item : items.subList(Math.max(0, items.size() - 10), items.size())) {
                apiClient.postToConnection(PostToConnectionRequest.builder().connectionId(input.getRequestContext().getConnectionId())
                        .data(SdkBytes.fromUtf8String("（過去ログ）" + item.get("text").s())).build());
            }
        }
    }

    private void broadcastToAll(APIGatewayV2WebSocketEvent input, Context context, DynamoDbClient dbClient, String message) {
        String callbackUrl = getCallbackUrl(input);
        ScanResponse scanResponse = dbClient.scan(ScanRequest.builder().tableName(CONN_TABLE).build());
        try (ApiGatewayManagementApiClient apiClient = ApiGatewayManagementApiClient.builder().endpointOverride(URI.create(callbackUrl)).build()) {
            for (Map<String, AttributeValue> item : scanResponse.items()) {
                String tid = item.get("connectionId").s();
                try {
                    apiClient.postToConnection(PostToConnectionRequest.builder().connectionId(tid).data(SdkBytes.fromUtf8String(message)).build());
                } catch (Exception e) { /* 自動クリーンアップは省略 */ }
            }
        }
    }

    private String getCallbackUrl(APIGatewayV2WebSocketEvent input) {
        return "https://" + input.getRequestContext().getDomainName() + "/" + input.getRequestContext().getStage();
    }

    private APIGatewayV2WebSocketResponse createResponse(int statusCode, String body) {
        APIGatewayV2WebSocketResponse response = new APIGatewayV2WebSocketResponse();
        response.setStatusCode(statusCode);
        response.setBody(body);
        return response;
    }
}