package com.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

import org.apache.hc.client5.http.fluent.Request; // 追加
import com.fasterxml.jackson.databind.JsonNode; // 追加
import com.fasterxml.jackson.databind.ObjectMapper; // 追加
import org.apache.hc.client5.http.fluent.Request; // これが重要です

public class App implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

@Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent input, Context context) {
        
        // REST API の「リソースパス」を取得（例: "/hello"）
        String resourcePath = input.getResource();
        context.getLogger().log("Target Resource: " + resourcePath);

        if ("/hello".equals(resourcePath)) {
            return createResponse(200, "{\"message\": \"Hello from REST API!\"}");
        } 
        
    if ("/search".equals(resourcePath)) {
            java.util.Map<String, String> queryParams = input.getQueryStringParameters();
            String zipcode = (queryParams != null) ? queryParams.get("zipcode") : "";

            if (zipcode.isEmpty()) {
                return createResponse(400, "{\"error\": \"zipcode is required\"}");
            }

            try {
                // 1. 外部APIを呼び出す
                String apiUrl = "https://zipcloud.ibsnet.co.jp/api/search?zipcode=" + zipcode;
                String resultJson = Request.get(apiUrl).execute().returnContent().asString();

                // 2. JSONを解析して住所を取り出す
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(resultJson);
                JsonNode results = root.get("results");

                String address = "見つかりませんでした";
                if (results != null && results.isArray() && results.size() > 0) {
                    JsonNode firstResult = results.get(0);
                    address = firstResult.get("address1").asText() + 
                              firstResult.get("address2").asText() + 
                              firstResult.get("address3").asText();
                }

                return createResponse(200, "{\"zipcode\": \"" + zipcode + "\", \"address\": \"" + address + "\"}");

            } catch (Exception e) {
                context.getLogger().log("API Error: " + e.getMessage());
                return createResponse(500, "{\"error\": \"External API connection failed\"}");
            }
        }

        return createResponse(404, "{\"error\": \"Path not defined in Java\"}");
    }

    private APIGatewayProxyResponseEvent createResponse(int statusCode, String body) {
        APIGatewayProxyResponseEvent response = new APIGatewayProxyResponseEvent();
        response.setStatusCode(statusCode);
        response.setBody(body);
        return response;
    }
}