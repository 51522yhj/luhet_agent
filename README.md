# luhet_agent

录合同智能体项目，包含 Spring Boot/Spring AI 后端和 Vue 前端页面。

## 项目结构

```text
.
├── heima-ai/                         # 后端项目 xiaoyu-ai
│   ├── docs/                         # 技术文档
│   ├── src/main/java/com/xy/ai/      # 后端源码
│   ├── src/main/resources/mapper/    # MyBatis Mapper XML
│   └── pom.xml
└── spring-ai-protal/spring-ai-protal/ # 前端 Vue 项目
    ├── src/views/CustomerService.vue # 录合同客服页面
    ├── src/services/api.js           # 后端接口调用
    └── package.json
```

## 主要功能

- 录合同 Agent：收集专家授课协议信息、识别医生、匹配角色、匹配 Q 计划活动、校验地区、风险检查、创建合同。
- 真流式输出：前端通过 `fetch + ReadableStream` 逐段展示后端 SSE 输出。
- 请求中断：前端支持生成过程中取消请求。
- 课程客服和通用聊天：保留原有课程咨询、游戏聊天、通用聊天入口。

## 后端运行

后端目录：

```powershell
cd heima-ai
```

JDK 使用 Dragonwell 21：

```powershell
$env:JAVA_HOME='C:\Users\LENOVO\.jdks\dragonwell-21.0.10'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
```

编译：

```powershell
mvn -q -DskipTests compile
```

启动：

```powershell
mvn spring-boot:run
```

默认端口：

```text
http://localhost:8081
```

录合同流式接口：

```http
GET /ai/record-contract-agent?prompt=你好&chatId=test-1
```

## 前端运行

前端目录：

```powershell
cd spring-ai-protal\spring-ai-protal
```

安装依赖：

```powershell
npm install
```

启动：

```powershell
npm run dev
```

访问：

```text
http://localhost:5173/customer-service
```

## 配置说明

本仓库按要求没有提交任何 `.yml` / `.yaml` 文件，因此后端启动前需要自行补充：

```text
heima-ai/src/main/resources/application.yaml
```

配置中通常需要包含：

- `spring.ai.openai.base-url`
- `spring.ai.openai.api-key`
- `spring.ai.openai.chat.options.model`
- `spring.datasource.*`
- `record-contract.http.endpoints.*`

请不要把真实 API Key、数据库密码等敏感信息提交到仓库。

## 文档

完整技术文档：

```text
heima-ai/docs/technical-documentation.md
```

录合同流程文档：

```text
heima-ai/docs/record-contract-flow.md
```

## 注意事项

- `teachDuration` 是用户输入的授课时长字符串，不由后端自动计算，也不自动追加单位。
- 创建合同前必须先调用风险检查，通过后还需要用户二次确认。
- 如果风险检查返回风险信息，创建合同时必须传入用户填写的 `limitReason`。
- 合同编号只能使用创建合同接口返回值，不能自行生成。
