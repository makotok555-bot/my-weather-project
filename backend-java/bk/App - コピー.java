package com.weather;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class App implements RequestHandler<Map<String, Object>, String> {

        private final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard()
        .withRegion("ap-northeast-1")
        .build();
    private final AmazonS3 s3 = AmazonS3ClientBuilder.standard()
        .withRegion("ap-northeast-1")
        .build();
    
    private static final String TABLE_NAME = "WeatherLog";
    private static final String BUCKET_NAME = "my-java-log-tokyo20260323"; // ここを自分のバケット名に！

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        // 1. パラメータ取得
        @SuppressWarnings("unchecked")
        Map<String, String> queryParams = (Map<String, String>) input.get("queryStringParameters");
        String zipCode = (queryParams != null) ? queryParams.getOrDefault("zipcode", "1000005") : "1000005";

        try {
            // 2. 郵便番号API呼び出し
            String url = "https://zipcloud.ibsnet.co.jp/api/search?zipcode=" + zipCode;
            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();
            
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(response.body());
            JsonNode resultNode = root.path("results").get(0);

            if (resultNode == null) return "住所が見つかりませんでした: " + zipCode;

            String address = resultNode.path("address1").asText() + 
                             resultNode.path("address2").asText() + 
                             resultNode.path("address3").asText();

            // 3. DynamoDB 保存
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("id", new AttributeValue(UUID.randomUUID().toString()));
            item.put("zipcode", new AttributeValue(zipCode));
            item.put("address", new AttributeValue(address));
            ddb.putItem(TABLE_NAME, item);

            // 4. S3 保存 (CSV形式)
            String csvContent = "zipcode,address\n" + zipCode + "," + address;
            String fileName = "log_" + System.currentTimeMillis() + ".csv";
            s3.putObject(BUCKET_NAME, fileName, csvContent);

            return "【成功】DBとS3( " + fileName + " )に保存しました！住所: " + address;

        } catch (Exception e) {
            context.getLogger().log("エラー: " + e.getMessage());
            return "処理に失敗しました。";
        }
    }
}