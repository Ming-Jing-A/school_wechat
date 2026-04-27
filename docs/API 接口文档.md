# School WeChat API 接口文档

## 基础信息

- **Base URL**: `http://服务器 IP:8080/api`
- **认证方式**: Bearer Token (在请求头中携带 `Authorization: Bearer {token}`)
- **数据格式**: JSON

---

## 1. 认证相关 (Auth)

### 1.1 用户登录
- **接口**: `POST /api/auth/login`
- **认证**: 不需要
- **请求体**:
```json
{
  "username": "用户名",
  "password": "密码"
}
```
- **响应**:
```json
{
  "success": true,
  "message": "登录成功",
  "data": {
    "token": "jwt_token",
    "user": {...}
  }
}
```

### 1.2 用户注册
- **接口**: `POST /api/auth/register`
- **认证**: 不需要
- **请求体**:
```json
{
  "username": "用户名",
  "password": "密码",
  "nickname": "昵称"
}
```

### 1.3 退出登录
- **接口**: `POST /api/auth/logout`
- **认证**: 需要

### 1.4 修改密码
- **接口**: `POST /api/auth/password/change`
- **认证**: 需要
- **请求体**:
```json
{
  "oldPassword": "旧密码",
  "newPassword": "新密码"
}
```

---

## 2. 用户信息 (User)

### 2.1 获取当前用户信息
- **接口**: `GET /api/users/me`
- **认证**: 需要
- **响应**:
```json
{
  "success": true,
  "data": {
    "id": 1,
    "username": "xxx",
    "nickname": "xxx",
    "avatarUrl": "xxx",
    "signature": "xxx",
    "wechatNo": "xxx",
    "phone": "xxx",
    "email": "xxx",
    "region": "xxx"
  }
}
```

### 2.2 更新用户资料
- **接口**: `POST /api/users/me/update`
- **认证**: 需要
- **请求体**:
```json
{
  "nickname": "昵称",
  "wechatNo": "微信号",
  "phone": "电话",
  "email": "邮箱",
  "avatarUrl": "头像 URL",
  "region": "地区",
  "signature": "个性签名"
}
```

---

## 3. 文件管理 (File)

### 3.1 上传文件
- **接口**: `POST /api/files/upload`
- **认证**: 需要
- **Content-Type**: `multipart/form-data`
- **请求参数**:
  - `file`: 文件对象 (必填)
  - `thumbnailFile`: 缩略图文件 (可选)
  - `durationSeconds`: 媒体时长秒数 (可选，用于音视频)
- **响应**:
```json
{
  "success": true,
  "data": {
    "id": 50,
    "fileName": "xxx.mp4",
    "mimeType": "video/mp4",
    "fileSize": 1234567,
    "fileUrl": "xxx",
    "thumbnailUrl": "xxx",
    "previewUrl": "/api/files/50/preview",
    "downloadUrl": "/api/files/50/download",
    "durationText": "00:22"
  }
}
```

### 3.2 获取文件信息
- **接口**: `GET /api/files/{fileId}`
- **认证**: 需要

### 3.3 获取文件访问信息
- **接口**: `GET /api/files/{fileId}/access`
- **认证**: 需要

### 3.4 预览文件
- **接口**: `GET /api/files/{fileId}/preview`
- **认证**: 需要
- **说明**: 返回文件内容，`Content-Disposition: inline`，适合直接在浏览器中播放视频/查看图片

### 3.5 下载文件
- **接口**: `GET /api/files/{fileId}/download`
- **认证**: 需要
- **说明**: 返回文件内容，`Content-Disposition: attachment`，会触发浏览器下载

---

## 4. 会话管理 (Conversation)

### 4.1 获取会话列表
- **接口**: `GET /api/conversations`
- **认证**: 需要
- **响应**:
```json
{
  "success": true,
  "data": [
    {
      "conversationId": "xxx",
      "type": "private|group",
      "lastMessageContent": "xxx",
      "lastMessageAt": "2026-04-23T...",
      "unreadCount": 0,
      "isTop": false
    }
  ]
}
```

### 4.2 获取会话消息列表
- **接口**: `GET /api/conversations/{conversationId}/messages`
- **认证**: 需要
- **查询参数**:
  - `beforeId`: 获取此消息 ID 之前的消息 (用于分页)
  - `limit`: 每次获取的消息数量 (默认 20)

### 4.3 发送消息
- **接口**: `POST /api/conversations/{conversationId}/messages`
- **认证**: 需要
- **请求体**:
```json
{
  "messageType": "text|image|video|file",
  "content": "消息内容",
  "fileResource": {...},
  "quoteMessageId": 123
}
```

### 4.4 删除消息
- **接口**: `DELETE /api/conversations/{conversationId}/messages/{messageId}`
- **认证**: 需要

### 4.5 置顶/取消置顶会话
- **接口**: `POST /api/conversations/{conversationId}/toggle-pin`
- **认证**: 需要

### 4.6 设置免打扰
- **接口**: `POST /api/conversations/{conversationId}/toggle-mute`
- **认证**: 需要

---

## 5. 好友管理 (Friend)

### 5.1 获取好友列表
- **接口**: `GET /api/friends`
- **认证**: 需要

### 5.2 发送好友申请
- **接口**: `POST /api/friend-requests`
- **认证**: 需要
- **请求体**:
```json
{
  "targetUsername": "对方用户名",
  "message": "申请消息"
}
```

### 5.3 获取好友申请列表
- **接口**: `GET /api/friend-requests`
- **认证**: 需要

### 5.4 处理好友申请
- **接口**: `POST /api/friend-requests/{requestId}`
- **认证**: 需要
- **请求体**:
```json
{
  "status": "accept|reject"
}
```

### 5.5 删除好友
- **接口**: `DELETE /api/friends/{friendId}`
- **认证**: 需要

---

## 6. 群聊管理 (Group)

### 6.1 创建群聊
- **接口**: `POST /api/groups`
- **认证**: 需要
- **请求体**:
```json
{
  "name": "群名称",
  "memberIds": [1, 2, 3]
}
```

### 6.2 获取群信息
- **接口**: `GET /api/groups/{groupId}`
- **认证**: 需要

### 6.3 更新群信息
- **接口**: `PUT /api/groups/{groupId}`
- **认证**: 需要

### 6.4 添加群成员
- **接口**: `POST /api/groups/{groupId}/members`
- **认证**: 需要
- **请求体**:
```json
{
  "memberIds": [1, 2, 3]
}
```

### 6.5 移除群成员
- **接口**: `DELETE /api/groups/{groupId}/members/{memberId}`
- **认证**: 需要

### 6.6 解散群聊
- **接口**: `DELETE /api/groups/{groupId}`
- **认证**: 需要

---

## 7. 通知 (Notification)

### 7.1 获取通知列表
- **接口**: `GET /api/notifications`
- **认证**: 需要

### 7.2 标记通知为已读
- **接口**: `POST /api/notifications/{notificationId}/read`
- **认证**: 需要

---

## 8. WebSocket 实时通信

### 8.1 连接地址
```
ws://服务器 IP:8080/ws/chat?token={jwt_token}
```

### 8.2 客户端发送消息格式
```json
{
  "type": "CHAT",
  "conversationId": "xxx",
  "content": "消息内容",
  "messageType": "text"
}
```

### 8.3 服务器推送消息格式
```json
{
  "type": "CHAT",
  "message": {
    "id": 123,
    "conversationId": "xxx",
    "senderUserId": 1,
    "content": "xxx",
    "messageType": "text",
    "sentAt": "2026-04-23T..."
  }
}
```

---

## 9. 错误码说明

| 错误码 | 说明 |
|--------|------|
| 200 | 成功 |
| 400 | 请求参数错误 |
| 401 | 未认证/Token 无效 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 500 | 服务器内部错误 |

---

## 10. 使用示例

### 10.1 登录并获取 Token
```bash
curl -X POST http://服务器 IP:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"test","password":"123456"}'
```

### 10.2 使用 Token 访问需要认证的接口
```bash
curl -X GET http://服务器 IP:8080/api/users/me \
  -H "Authorization: Bearer {token}"
```

### 10.3 上传文件
```bash
curl -X POST http://服务器 IP:8080/api/files/upload \
  -H "Authorization: Bearer {token}" \
  -F "file=@/path/to/file.mp4"
```

### 10.4 WebSocket 连接 (JavaScript)
```javascript
const ws = new WebSocket(`ws://服务器 IP:8080/ws/chat?token=${token}`);
ws.onopen = () => {
  console.log('WebSocket 已连接');
};
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log('收到消息:', data);
};
```
