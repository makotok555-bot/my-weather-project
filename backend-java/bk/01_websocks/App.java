package com.weather;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2WebSocketResponse;

public class App implements RequestHandler<APIGatewayV2WebSocketEvent, APIGatewayV2WebSocketResponse> {

    @Override
    public APIGatewayV2WebSocketResponse handleRequest(APIGatewayV2WebSocketEvent input, Context context) {
        // どのルート（$connect, $disconnect, sendmessage）から来たか取得
        String routeKey = input.getRequestContext().getRouteKey();
        String connectionId = input.getRequestContext().getConnectionId();

        context.getLogger().log("WebSocket Route: " + routeKey + " [ID: " + connectionId + "]");

        // 1. 接続・切断時の処理（200を返せば接続が維持される）
        if ("$connect".equals(routeKey) || "$disconnect".equals(routeKey)) {
            return createResponse(200, "Success");
        }

        // 2. メッセージが来た時（sendmessage）の処理
        if ("sendmessage".equals(routeKey)) {
            String body = input.getBody();
            context.getLogger().log("Received Message: " + body);
            
            // 簡易的なオウム返しレスポンス
            return createResponse(200, "Echo: " + body);
        }

        return createResponse(200, "OK");
    }

    private APIGatewayV2WebSocketResponse createResponse(int statusCode, String body) {
        APIGatewayV2WebSocketResponse response = new APIGatewayV2WebSocketResponse();
        response.setStatusCode(statusCode);
        response.setBody(body);
        return response;
    }
}