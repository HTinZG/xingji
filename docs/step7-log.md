# 第 7 步执行日志：AI 聊天

## 7.1 Key 的处理方式（最重要的一条）

全程 **没有让 Key 进入任何文件、日志、指令或截图**：

| 环节 | 做法 |
|---|---|
| 存储 | Android Keystore AES-256-GCM 加密后的密文，存 DataStore |
| 请求 | 只在 `Authorization: Bearer` 头里出现，不进 URL、不进 body |
| 日志 | **没有加任何 OkHttp logging interceptor**（默认就会打 Authorization） |
| 假服务器 | 记录请求时先脱敏成 `Bearer sk-***(12 chars)` |
| 截图 | 只拍 App 界面，未接触真实 Key |

Keystore 别名固定 `xingji_ai_key`，`setUserAuthenticationRequired(false)`——
锁屏状态也能用，不需要指纹/密码解锁，符合"不需要用户认证"的要求。

刻意**没有**用 EncryptedSharedPreferences：那个库已停止维护。

## 7.2 设置页

三个输入框：BaseURL / 模型名 / API Key。全部留空，等用户自己填。
Key 输入框用 `PasswordVisualTransformation`，保存后回显为空，占位提示「已保存，留空表示不修改」。

## 7.3 BaseURL 校验

| 输入 | 结果 |
|---|---|
| 空 | 拒绝：「请填写 BaseURL」 |
| 缺 scheme（`api.xxx.com`） | 拒绝：「必须以 http:// 或 https:// 开头」 |
| 有查询参数 | 拒绝：「BaseURL 不要带查询参数」 |
| `https://...` | 直接保存 |
| `http://...` | **弹窗警告**，用户点「我知道风险，继续」才保存 |

弹窗文案：

> **这是明文传输**
> http:// 不加密。你和服务器之间的任何一跳都能看到 Key 和行程内容，公共 Wi-Fi 下尤其危险。
> 确定要用 http://127.0.0.1:8765/v1 吗？
> [改用 https]　[我知道风险，继续]

scheme 统一转小写，路径大小写原样保留。

## 7.4 超时与错误映射

连接 10 秒 / 读 60 秒 / 整体 90 秒。

| 情况 | 界面显示 | 验证 |
|---|---|---|
| HTTP 401 | Key 无效或已过期 | ✅ 截图 |
| HTTP 429 | 请求太频繁 | 代码路径同上 |
| 读超时（60s） | 网络超时 | ✅ 截图（等到 60 秒真的触发） |
| 其它状态码 | 服务返回 HTTP xxx | — |
| 连接失败 | 网络错误：<原因> | 测试中真实出现过 CLEARTEXT 被拒 |

## 7.5 上下文：只发摘要

system prompt 里放的是**摘要**，不是轨迹点：

- 最近 10 条行程，每条一行：开始时间、时长、距离、均速、分段数、停留数、信号中断次数
- 最近 1 条行程的分段明细：每段的模式、起止时刻、时长、距离
- 停留点的时间段

实测 system 部分 **761 个字符**，而不是几千个坐标点。

## 7.6 首次使用隐私提示

第一次发送前必弹：

> **发出去之前说一声**
> 你的行程摘要（距离、时长、分段等，不含原始轨迹点）会发送到你自己配置的 API 服务商。
> App 不做任何中转，也不保存这次请求。
> [取消]　[同意并发送]

同意后写进 DataStore（`disclosure_accepted`），之后不再弹。

## 7.7 聊天记录

`ai_chat_message` 表：`role / content / createdAt / tripId(可选)`。
数据库版本 2 → 3，迁移只 `CREATE TABLE`，不动已有数据。
不进导出文件、清空数据时一起删。

## 7.8 假服务器验证（Key 已脱敏）

本地 Node 假服务 + `adb reverse tcp:8765 tcp:8765`，BaseURL 指向 `http://127.0.0.1:8765/v1`。

App 实际发出的请求（服务端记录，Authorization 已脱敏）：

```json
{
  "time": "2026-11-16T12:47:27.712Z",
  "method": "POST",
  "path": "/v1/chat/completions",
  "auth": "Bearer sk-***(12 chars)",
  "contentType": "application/json; charset=utf-8",
  "model": "fake-model",
  "temperature": 0.3,
  "messageRoles": ["system", "user", "user", "user"],
  "systemChars": 761,
  "userPreview": "How many"
}
```

对照检查：

- ✅ URL 是 `BaseURL + /chat/completions`
- ✅ Key 只在 Authorization 头，长度 12（假值 `sk-test-fake`），日志里是脱敏形式
- ✅ `model` 用了设置里填的值
- ✅ 第一条是 system（摘要），后面是历史 + 当前问题
- ✅ 请求头 `Content-Type: application/json; charset=utf-8`

## 7.9 真机结果

| 场景 | 结果 | 截图 |
|---|---|---|
| 设置页三个输入框 | ✅ | 30-ai-settings.png |
| http 明文警告 | ✅ | 31-ai-http-warning.png |
| 没配 Key 的引导 | ✅ | 32-ai-no-key-guide.png |
| 首次隐私提示 | ✅ | 33-ai-privacy-disclosure.png |
| 正常对话 | ✅ 回复引用了行程摘要 | 34-ai-chat-success.png |
| 401 | ✅ Key 无效或已过期 | 35-ai-error-401.png |
| 读超时 | ✅ 网络超时 | 36-ai-timeout.png |

## 7.10 过程中修掉的三个真问题

1. **设置页缺 `imePadding`**：键盘弹出时「保存」被挡住，小屏用户根本点不到。已加。
2. **DataStore 文件损坏会直接崩 App**：手改设置文件验证时踩到
   `InvalidProtocolBufferException`，整个进程挂掉。已加 `ReplaceFileCorruptionHandler`，
   损坏时回退成空配置而不是崩溃。
3. **明文 HTTP 被系统拦截**：`CLEARTEXT communication not permitted`。
   加了 `network_security_config.xml`，注释里写明"因为 BaseURL 由用户填，
   且保存 http 时强制二次确认"。影响范围仅限用户手动配置的 AI 请求。

## 7.11 明确没做

- 多轮工具调用（function calling）
- 流式输出（非流式，一次性返回）
- AI 自动生成行程标题
- 任何形式的代理/中转
