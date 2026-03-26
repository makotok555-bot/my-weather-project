import json
import boto3
import urllib.request
import uuid
from datetime import datetime

# AWSリソースの準備
dynamodb = boto3.resource('dynamodb')
conn_table = dynamodb.Table('WebSocketConnections')
history_table = dynamodb.Table('ChatHistory')

def lambda_handler(event, context):
    rc = event.get('requestContext', {})
    route_key = rc.get('routeKey')
    conn_id = rc.get('connectionId')
    domain = rc.get('domainName')
    stage = rc.get('stage')
    endpoint_url = f"https://{domain}/{stage}"

    # API Gateway Management APIクライアント
    apigw = boto3.client('apigatewaymanagementapi', endpoint_url=endpoint_url)

    try:
        if route_key == '$connect':
            conn_table.put_item(Item={'connectionId': conn_id})
            
        elif route_key == '$disconnect':
            conn_table.delete_item(Key={'connectionId': conn_id})
            
        elif route_key == 'gethistory':
            # 履歴を取得してソート
            res = history_table.scan()
            items = sorted(res.get('Items', []), key=lambda x: int(x['timestamp']))
            for item in items[-10:]:
                apigw.post_to_connection(
                    ConnectionId=conn_id,
                    Data=f"（過去ログ）{item['text']}".encode('utf-8')
                )
                
        elif route_key == 'sendmessage':
            body = json.loads(event.get('body', '{}'))
            user = body.get('userName', '匿名')
            content = body.get('data', '')
            
            # メッセージ保存 & 全員配信
            full_msg = f"[{user}] {content}"
            save_history(full_msg)
            broadcast(apigw, full_msg)

            # お天気ボット判定
            if content.startswith('!tenki '):
                zip_code = content.replace('!tenki ', '').strip()
                weather_info = fetch_weather(zip_code)
                bot_msg = f"[お天気ボット] {weather_info}"
                save_history(bot_msg)
                broadcast(apigw, bot_msg)

    except Exception as e:
        print(f"Error: {str(e)}")

    return {'statusCode': 200, 'body': 'Connected'}

def save_history(text):
    history_table.put_item(Item={
        'messageId': str(uuid.uuid4()),
        'timestamp': int(datetime.now().timestamp()),
        'text': text
    })

def broadcast(apigw, message):
    conns = conn_table.scan().get('Items', [])
    for c in conns:
        try:
            apigw.post_to_connection(
                ConnectionId=c['connectionId'],
                Data=message.encode('utf-8')
            )
        except apigw.exceptions.GoneException:
            conn_table.delete_item(Key={'connectionId': c['connectionId']})

def fetch_weather(zip_code):
    try:
        # 1. 郵便番号から座標取得
        geo_url = f"http://geoapi.heartrails.com/api/json?method=searchByPostal&postal={zip_code}"
        with urllib.request.urlopen(geo_url) as res:
            geo_data = json.loads(res.read().decode())['response']['location'][0]
            lat, lon = geo_data['y'], geo_data['x']
            city = f"{geo_data['prefecture']}{geo_data['city']}{geo_data['town']}"

        # 2. 天気取得
        w_url = f"https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&current_weather=true"
        with urllib.request.urlopen(w_url) as res:
            w_data = json.loads(res.read().decode())['current_weather']
            temp = w_data['temperature']
            return f"{city}の天気コードは{w_data['weathercode']}、気温は{temp}℃です！"
    except:
        return f"郵便番号[{zip_code}]が見つかりませんでした。"