# School Wechat Frontend Protocol

## 1. Overview

This document describes the current frontend integration protocol for the `school_wechat` backend.

Current communication model:

- `HTTP API`: login, loading data, sending messages, recalling messages, creating groups, handling friend requests
- `WebSocket`: real-time push for chat messages, recalled messages, conversation state updates, notifications, sync events

Current WebSocket direction:

- frontend -> backend: only heartbeat `ping`
- backend -> frontend: push events

This means the frontend should still send business actions through HTTP, and use WebSocket only to receive real-time updates.

## 2. Base URL

- HTTP: `http://localhost:8080`
- WebSocket: `ws://localhost:8080/ws/chat`

If the service is started on another port, replace the port accordingly.

## 3. Common Response Format

All HTTP APIs return the same wrapper:

```json
{
  "success": true,
  "message": "success",
  "data": {}
}
```

Error example:

```json
{
  "success": false,
  "message": "未登录或登录已过期",
  "data": null
}
```

## 4. Authentication

### 4.0 Auth API List

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/logout`
- `POST /api/auth/password/change`
- `GET /api/users/me`
- `POST /api/users/me/update`

### 4.1 Login

- Method: `POST`
- Path: `/api/auth/login`

Request body:

```json
{
  "username": "zhangsan",
  "password": "zhangsan123",
  "deviceName": "Chrome Web",
  "browserName": "Chrome",
  "osName": "macOS"
}
```

Response example:

```json
{
  "success": true,
  "message": "登录成功",
  "data": {
    "userId": 1,
    "deviceId": 4,
    "token": "token_xxx",
    "username": "zhangsan",
    "nickname": "张三",
    "avatarUrl": "https://example.com/avatar/zhangsan.png",
    "wechatNo": "wechat_zhangsan"
  }
}
```

### 4.2 Register

- Method: `POST`
- Path: `/api/auth/register`

Request body:

```json
{
  "username": "new_user",
  "password": "new_user123",
  "nickname": "新同学",
  "wechatNo": "wx_new_user",
  "phone": "13800138000",
  "email": "new_user@example.com",
  "avatarUrl": null
}
```

Response structure is the same as login and returns token directly.

### 4.3 Change Password

- Method: `POST`
- Path: `/api/auth/password/change`

Request body:

```json
{
  "oldPassword": "zhangsan123",
  "newPassword": "zhangsan456"
}
```

### 4.4 Current User Profile

- Method: `GET`
- Path: `/api/users/me`

Response example:

```json
{
  "success": true,
  "message": "success",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "nickname": "张三",
    "wechatNo": "wx_zhangsan",
    "phone": "13800138000",
    "email": "zhangsan@example.com",
    "avatarUrl": "https://example.com/avatar/zhangsan.png",
    "gender": 1,
    "birthday": "2004-01-01",
    "region": "广东 深圳",
    "signature": "保持热爱",
    "friendAddPolicy": "need_confirm",
    "status": 1,
    "lastOnlineAt": "2026-04-14T10:00:00"
  }
}
```

### 4.5 Update Profile

- Method: `POST`
- Path: `/api/users/me/update`

Request body:

```json
{
  "nickname": "张三同学",
  "wechatNo": "wx_zhangsan",
  "phone": "13800138001",
  "email": "zhangsan@example.com",
  "avatarUrl": "https://example.com/avatar/zhangsan.png",
  "gender": 1,
  "birthday": "2004-01-01",
  "region": "广东 深圳",
  "signature": "保持热爱",
  "friendAddPolicy": "need_confirm"
}
```

### 4.2 HTTP Auth Header

For all protected HTTP APIs:

```http
Authorization: Bearer {token}
```

### 4.3 WebSocket Auth

Current backend supports 3 ways:

- query string: `ws://localhost:8080/ws/chat?token={token}`
- header: `Authorization: Bearer {token}`
- header: `X-Token: {token}`

For browsers, query string is the simplest and recommended option.

## 5. HTTP API List

### 5.1 User

- `GET /api/users/me`

Returns current logged-in user information.

### 5.2 Friends

- `GET /api/friends`
- `GET /api/users/search`
- `GET /api/friend-requests`
- `POST /api/friend-requests`
- `POST /api/friend-requests/{requestId}/handle`

Send friend request example:

```json
{
  "toUserId": 2,
  "requestMessage": "你好，想加你为好友",
  "source": "search"
}
```

Search user example:

```text
GET /api/users/search?keyword=zhangsan
```

Search user supports:

- user id
- username
- wechat number
- nickname

Handle friend request example:

```json
{
  "action": "accept"
}
```

`action` currently supports:

- `accept`
- `reject`

### 5.3 Conversations

- `GET /api/conversations`
- `POST /api/conversations/group`
- `POST /api/conversations/{conversationId}/top`
- `POST /api/conversations/{conversationId}/top/cancel`
- `POST /api/conversations/{conversationId}/mute`
- `POST /api/conversations/{conversationId}/mute/cancel`
- `POST /api/conversations/{conversationId}/hide`
- `POST /api/conversations/{conversationId}/hide/cancel`
- `POST /api/conversations/{conversationId}/draft`
- `POST /api/conversations/{conversationId}/unread/clear`
- `POST /api/conversations/{conversationId}/messages/clear`
- `POST /api/conversations/{conversationId}/delete`
- `GET /api/conversations/{conversationId}/detail`
- `GET /api/conversations/{conversationId}/members`
- `POST /api/conversations/{conversationId}/join`
- `GET /api/conversations/{conversationId}/join-requests`
- `POST /api/conversations/{conversationId}/join-requests/{requestId}/handle`
- `POST /api/conversations/{conversationId}/members/invite`
- `POST /api/conversations/{conversationId}/members/{memberUserId}/role`
- `POST /api/conversations/{conversationId}/members/{memberUserId}/mute`
- `POST /api/conversations/{conversationId}/members/{memberUserId}/remove`
- `POST /api/conversations/{conversationId}/leave`
- `POST /api/conversations/{conversationId}/announcement`
- `POST /api/conversations/{conversationId}/mute-all`
- `POST /api/conversations/{conversationId}/owner/transfer`
- `GET /api/conversations/{conversationId}/messages`
- `GET /api/conversations/{conversationId}/messages/search`
- `GET /api/conversations/{conversationId}/messages/{messageId}/read-receipt`
- `POST /api/conversations/{conversationId}/messages/{messageId}/delete-for-me`
- `POST /api/conversations/{conversationId}/messages`
- `POST /api/conversations/{conversationId}/messages/{messageId}/recall`

Create group request example:

```json
{
  "name": "Spring Boot 学习群",
  "announcement": "欢迎加入群聊",
  "memberUserIds": [2, 3]
}
```

Send message request example:

```json
{
  "messageType": "text",
  "content": "你好，这是测试消息",
  "quoteMessageId": null,
  "fileId": null,
  "mentionUserIds": []
}
```

Quote message example:

```json
{
  "messageType": "text",
  "content": "收到，我晚点处理",
  "quoteMessageId": 101,
  "fileId": null,
  "mentionUserIds": []
}
```

File message example:

```json
{
  "messageType": "file",
  "content": "[文件] project-plan.pdf",
  "fileId": 1,
  "mentionUserIds": []
}
```

Media message rules:

- `image` must use an uploaded image file
- `voice` must use an uploaded audio file
- `video` must use an uploaded video file
- `file` should use an uploaded file resource
- for `image` / `voice` / `video` / `file`, backend auto-fills `content` when request body leaves it empty

Auto-filled content:

- `image` -> `[图片]`
- `voice` -> `[语音]`
- `video` -> `[视频]`
- `file` -> `[文件] {fileName}`

Recall message:

- no request body is required
- an empty JSON object `{}` is acceptable

Message pagination query parameters:

- `beforeMessageId`: optional, load messages older than this message
- `limit`: optional, default `20`, max `50`

Example:

```text
GET /api/conversations/1/messages?beforeMessageId=120&limit=20
```

Message search query parameters:

- `keyword`: optional, searches message text, sender nickname and file name
- `messageType`: optional, such as `text`, `image`, `file`, `voice`, `video`
- `beforeMessageId`: optional cursor for older matched results
- `limit`: optional, default `20`, max `50`

Example:

```text
GET /api/conversations/1/messages/search?keyword=课程表&messageType=file&limit=20
```

Search result uses the same `ConversationMessagePageView` structure as normal message pagination.

Current search rule:

- search is scoped inside one conversation
- search does not clear unread count
- search respects `delete-for-me` and `clear_message_before`

### 5.4 Files

- `POST /api/files/upload`
- `POST /api/files/mock-upload`
- `GET /api/files/{fileId}`
- `GET /api/files/{fileId}/access`
- `GET /api/files/{fileId}/preview`
- `GET /api/files/{fileId}/download`

Current backend supports local file upload and also keeps the old mock metadata API.

Local upload:

- request type: `multipart/form-data`
- field `file`: required
- field `thumbnailFile`: optional, mainly for video cover
- field `durationSeconds`: optional, mainly for voice/video duration

Upload validation:

- only video files can carry `thumbnailFile`
- `thumbnailFile` must be an image
- `durationSeconds` is only allowed for voice/video files
- `durationSeconds` must be greater than `0`

Typical local upload result:

```json
{
  "id": 12,
  "fileName": "voice-001.mp3",
  "fileExt": "mp3",
  "mimeType": "audio/mpeg",
  "fileUrl": "http://localhost:8081/uploads/voice/2026/04/xxxx.mp3",
  "thumbnailUrl": null,
  "width": null,
  "height": null,
  "durationSeconds": 8
}
```

Local storage rule:

- uploaded files are stored in local `uploads/` directory
- browser can directly access uploaded files through `/uploads/**`
- backend also provides authenticated preview/download endpoints

File access info example:

```json
{
  "fileId": 6,
  "fileName": "demo-video.mp4",
  "mimeType": "video/mp4",
  "fileSize": 21,
  "fileSizeText": "21 B",
  "fileUrl": "http://localhost:8081/uploads/video/2026/04/xxxx.mp4",
  "thumbnailUrl": "http://localhost:8081/uploads/thumbnail/2026/04/yyyy.png",
  "previewUrl": "http://localhost:8081/api/files/6/preview",
  "downloadUrl": "http://localhost:8081/api/files/6/download",
  "previewable": true,
  "localFileExists": true,
  "fileStatus": "available",
  "fileStatusMessage": "文件可访问",
  "mediaCardType": "video",
  "durationText": "00:12",
  "displayThumbnailUrl": "http://localhost:8081/uploads/thumbnail/2026/04/yyyy.png",
  "fallbackIcon": "video"
}
```

Access rules:

- `preview` returns inline file content for local files
- `download` returns attachment response for local files
- `access` is the recommended API for frontend buttons because it gives both URLs and preview capability
- `fileStatus` values:
- `available`: local file can be accessed
- `missing`: local file record exists but actual file is gone
- `external`: file points to external URL
- if `fileStatus = missing`, frontend should disable preview/download buttons and show `fileStatusMessage`
- `mediaCardType` values: `image` / `voice` / `video` / `file`
- `durationText` is mainly for `voice` and `video`
- `displayThumbnailUrl` is preferred cover for `image` and `video`
- `fallbackIcon` is the icon type frontend can directly use when no cover is available

Create mock file example:

```json
{
  "fileName": "project-plan.pdf",
  "fileExt": "pdf",
  "mimeType": "application/pdf",
  "fileSize": 102400,
  "fileUrl": "https://example.com/files/project-plan.pdf",
  "thumbnailUrl": null
}
```

Mock upload note:

- `mock-upload` is treated as external file metadata
- its `fileStatus` will normally be `external`
- it is useful when frontend already has an external file URL

### 5.5 Notifications

- `GET /api/notifications`
- `POST /api/notifications/{notificationId}/read`

### 5.6 Sync

- `GET /api/sync/events?fromSeq=0&limit=50`

This endpoint is useful when the frontend reconnects and wants to pull missed changes.

## 6. Important Response Models

### 6.1 Conversation Summary

Returned by `GET /api/conversations` and also pushed by WebSocket event `conversation_state`.

```json
{
  "conversationId": 1,
  "conversationType": "single",
  "conversationName": "张三同学",
  "avatarUrl": "https://example.com/avatar/zhangsan.png",
  "announcement": null,
  "lastMessageType": "text",
  "lastMessageContent": "你好，这是测试消息",
  "lastSenderId": 1,
  "lastMessageAt": "2026-04-13T19:46:22",
  "unreadCount": 1,
  "isTop": 0,
  "isMuted": 0,
  "isHidden": 0,
  "draftContent": null
}
```

Conversation summary display rules:

- if `draftContent` is not empty, backend returns `lastMessageContent` as `[草稿] {draftContent}`
- `image` messages are summarized as `[图片]`
- `file` messages are summarized as `[文件] xxx`
- `voice` messages are summarized as `[语音]`
- `video` messages are summarized as `[视频]`
- `system` messages are summarized as `[系统消息] xxx`
- recalled messages are summarized as `[消息已撤回]`

### 6.2 Conversation Message

Returned inside `GET /api/conversations/{conversationId}/messages` page result and pushed by `chat_message` or `message_recalled`.

```json
{
  "messageId": 7,
  "conversationId": 1,
  "senderUserId": 1,
  "senderNickname": "张三",
  "senderAvatarUrl": "https://example.com/avatar/zhangsan.png",
  "messageType": "text",
  "messageStatus": "sent",
  "content": "这是一次撤回测试消息",
  "contentJson": null,
  "quoteMessageId": null,
  "quoteSenderUserId": null,
  "quoteSenderNickname": null,
  "quoteMessageType": null,
  "quoteMessageContent": null,
  "sentAt": "2026-04-13T19:46:22",
  "fileId": null,
  "fileName": null,
  "fileUrl": null,
  "mimeType": null,
  "thumbnailUrl": null,
  "width": null,
  "height": null,
  "durationSeconds": null,
  "fileAccess": null
}
```

When a message has file/media attachment, backend also embeds `fileAccess`:

```json
{
  "fileId": 6,
  "fileName": "demo-video.mp4",
  "mimeType": "video/mp4",
  "fileSize": null,
  "fileSizeText": "0 B",
  "fileUrl": "http://localhost:8081/uploads/video/2026/04/xxxx.mp4",
  "thumbnailUrl": "http://localhost:8081/uploads/thumbnail/2026/04/yyyy.png",
  "previewUrl": "/api/files/6/preview",
  "downloadUrl": "/api/files/6/download",
  "previewable": true,
  "localFileExists": true,
  "fileStatus": "available",
  "fileStatusMessage": "文件可访问",
  "mediaCardType": "video",
  "durationText": "00:12",
  "displayThumbnailUrl": "http://localhost:8081/uploads/thumbnail/2026/04/yyyy.png",
  "fallbackIcon": "video"
}
```

Frontend recommendation:

- prefer using message-level `fileAccess` first
- if `fileAccess` is missing or stale, call `GET /api/files/{fileId}/access` again
- voice card: show `durationText`
- image card: prefer `displayThumbnailUrl`, click preview
- video card: prefer `displayThumbnailUrl`, if empty use `fallbackIcon = video`

Quoted message example:

```json
{
  "messageId": 108,
  "conversationId": 1,
  "senderUserId": 2,
  "senderNickname": "李四",
  "senderAvatarUrl": "https://example.com/avatar/lisi.png",
  "messageType": "text",
  "messageStatus": "sent",
  "content": "收到，我晚点处理",
  "contentJson": null,
  "quoteMessageId": 101,
  "quoteSenderUserId": 1,
  "quoteSenderNickname": "张三",
  "quoteMessageType": "file",
  "quoteMessageContent": "[文件] 课程表.pdf",
  "sentAt": "2026-04-14T09:00:00",
  "fileId": null,
  "fileName": null,
  "fileUrl": null,
  "mimeType": null,
  "thumbnailUrl": null,
  "width": null,
  "height": null,
  "durationSeconds": null
}
```

Delete-for-me behavior:

- only affects current user
- deleted message disappears from current user's message list
- other members still keep the message
- conversation summary is not globally changed

Group management request examples:

```json
{
  "requestMessage": "想加入学习交流群"
}
```

```json
{
  "action": "accept"
}
```

```json
{
  "memberRole": "admin"
}
```

```json
{
  "muted": 1,
  "muteMinutes": 30
}
```

```json
{
  "muteAll": 1
}
```

```json
{
  "targetUserId": 2
}
```

Group management rules:

- owner can set/cancel admin
- owner can transfer owner to an active member
- owner can mute admin/member, admin can mute member
- owner/admin can switch `muteAll`
- `joinRule = direct` joins immediately
- `joinRule = approval` creates join request
- `joinRule = invite_only` rejects self-join request

Recalled message example:

```json
{
  "messageId": 7,
  "conversationId": 1,
  "senderUserId": 1,
  "senderNickname": "张三",
  "senderAvatarUrl": "https://example.com/avatar/zhangsan.png",
  "messageType": "revoke",
  "messageStatus": "recalled",
  "content": "[消息已撤回]",
  "contentJson": null,
  "quoteMessageId": null,
  "sentAt": "2026-04-13T19:46:22",
  "fileId": null,
  "fileName": null,
  "fileUrl": null
}
```

### 6.3 Message Page

Returned by `GET /api/conversations/{conversationId}/messages`.

```json
{
  "list": [
    {
      "messageId": 101,
      "conversationId": 1,
      "senderUserId": 1,
      "senderNickname": "张三",
      "senderAvatarUrl": "https://example.com/avatar/zhangsan.png",
      "messageType": "text",
      "messageStatus": "sent",
      "content": "你好",
      "contentJson": null,
      "quoteMessageId": null,
      "sentAt": "2026-04-14T08:20:00",
      "fileId": null,
      "fileName": null,
      "fileUrl": null
    }
  ],
  "limit": 20,
  "nextBeforeMessageId": 101,
  "hasMore": true
}
```

Rules:

- first load: call without `beforeMessageId`
- load older messages: use returned `nextBeforeMessageId`
- stop requesting when `hasMore = false`

### 6.4 Message Read Receipt

Returned by `GET /api/conversations/{conversationId}/messages/{messageId}/read-receipt`.

```json
{
  "messageId": 101,
  "readCount": 2,
  "unreadCount": 1,
  "readers": [
    {
      "userId": 2,
      "nickname": "李四",
      "avatarUrl": "https://example.com/avatar/lisi.png",
      "readAt": "2026-04-14T08:21:00"
    }
  ]
}
```

Current rule:

- sender is not counted into `readCount` or `unreadCount`
- backend now also pushes WebSocket read-receipt updates when unread messages become read

## 7. WebSocket Protocol

### 7.1 Connect

Recommended frontend connection:

```text
ws://localhost:8080/ws/chat?token={token}
```

### 7.2 Envelope Format

All server push messages use the same envelope:

```json
{
  "type": "chat_message",
  "timestamp": "2026-04-13T19:00:00",
  "data": {}
}
```

### 7.3 Frontend -> Backend

Current supported client message:

```json
{
  "type": "ping"
}
```

Server response:

```json
{
  "type": "pong",
  "timestamp": "2026-04-13T19:00:00",
  "data": {
    "serverTime": "2026-04-13T19:00:00"
  }
}
```

### 7.4 Backend -> Frontend Event Types

#### `connected`

Sent immediately after WebSocket connection is established.

```json
{
  "type": "connected",
  "timestamp": "2026-04-13T19:00:00",
  "data": {
    "userId": 1,
    "deviceId": 4,
    "sessionId": "abc123"
  }
}
```

#### `chat_message`

Sent when a new message is created.

`data` structure uses `ConversationMessageView`.

#### `message_recalled`

Sent when a message is recalled.

`data` structure also uses `ConversationMessageView`.

Frontend handling recommendation:

- locate message by `messageId`
- replace `messageType` with `revoke`
- replace `messageStatus` with `recalled`
- replace text content with `[消息已撤回]`

#### `conversation_state`

Sent when conversation list state changes, for example:

- new message
- unread count change
- last message summary change
- message recall changes last message
- read operation clears unread count
- hide/delete-for-me state change
- draft/top/mute state change

`data` structure uses `ConversationSummaryView`.

Frontend handling recommendation:

- locate conversation by `conversationId`
- replace the whole conversation summary
- if `isHidden = 1`, remove it from the conversation list
- if not found and `isHidden = 0`, insert it into the conversation list
- sort conversation list again if your UI sorts by top/unread/last time

#### `message_read_receipt`

Sent when a user reads previously unread messages in a conversation.

```json
{
  "type": "message_read_receipt",
  "timestamp": "2026-04-14T11:00:00",
  "data": {
    "conversationId": 1,
    "messageId": 101,
    "readUserId": 2,
    "readUserNickname": "李四",
    "readUserAvatarUrl": "https://example.com/avatar/lisi.png",
    "readAt": "2026-04-14T11:00:00",
    "readCount": 1,
    "unreadCount": 0
  }
}
```

Frontend handling recommendation:

- locate message by `messageId`
- update local read count and unread count
- if UI shows reader avatars or names, merge `readUserId` and `readAt` into local state

#### `notification`

Sent when a new user notification is created.

`data` structure uses `UserNotificationView`.

Typical scenes:

- friend request
- group invite
- mention

#### `sync_event`

Sent when backend records a sync event for the current user.

`data` structure uses `UserSyncEventView`.

Suggested use:

- keep a local `lastSyncSeq`
- after reconnect, call `/api/sync/events?fromSeq={lastSyncSeq}`
- merge missed updates into current UI state

#### `server_notice`

Current backend may send this for unsupported WebSocket client messages.

## 8. Recommended Frontend Integration Flow

### 8.1 App Startup

1. Call `POST /api/auth/login`
2. Save `token`, `userId`, `deviceId`
3. Call `GET /api/users/me`
4. Connect WebSocket with `token`
5. Load initial data:
   - `GET /api/conversations`
   - `GET /api/friends`
   - `GET /api/friend-requests`
   - `GET /api/notifications`

### 8.2 Open Conversation

1. Call `GET /api/conversations/{conversationId}/messages`
2. Backend clears unread count for current user
3. Frontend receives or refreshes `conversation_state`
4. Other online members receive `message_read_receipt` for messages that became read
5. If user scrolls upward, call `GET /api/conversations/{conversationId}/messages?beforeMessageId={nextBeforeMessageId}&limit=20`

### 8.2 Conversation Settings

Typical operations:

- top: `POST /api/conversations/{conversationId}/top`
- cancel top: `POST /api/conversations/{conversationId}/top/cancel`
- mute: `POST /api/conversations/{conversationId}/mute`
- cancel mute: `POST /api/conversations/{conversationId}/mute/cancel`
- hide: `POST /api/conversations/{conversationId}/hide`
- cancel hide: `POST /api/conversations/{conversationId}/hide/cancel`
- save draft: `POST /api/conversations/{conversationId}/draft`
- clear unread: `POST /api/conversations/{conversationId}/unread/clear`
- clear my messages: `POST /api/conversations/{conversationId}/messages/clear`
- delete for me: `POST /api/conversations/{conversationId}/delete`

Delete-for-me behavior:

- only affects current user
- conversation disappears from current user's list
- top state and draft are cleared
- later new messages can make the conversation reappear

### 8.3 Send Message

1. Call `POST /api/conversations/{conversationId}/messages`
2. Backend writes DB
3. Online members receive:
   - `chat_message`
   - `conversation_state`
   - maybe `notification` if mentioned
   - `sync_event`

### 8.4 Recall Message

1. Call `POST /api/conversations/{conversationId}/messages/{messageId}/recall`
2. Backend updates message state to recalled
3. Online members receive:
   - `message_recalled`
   - `conversation_state`
   - `sync_event`

## 9. Current Limits

- WebSocket does not yet support direct business commands such as sending chat messages
- Real file upload is not implemented yet, only metadata creation is supported
- There is no dedicated typing indicator yet
- There is no read receipt push event yet, only HTTP query is available

## 10. Suggested Frontend Event Handling

- `chat_message`: append message, refresh conversation summary
- `message_recalled`: update message in place, refresh conversation summary
- `conversation_state`: overwrite conversation list item
- `message_read_receipt`: update per-message read counters and reader state
- `notification`: increase notification badge and insert new notification
- `sync_event`: update local sync cursor
- `pong`: refresh connection heartbeat state
