# AFT Studio

AFT Studio 是一个本地 API 业务流程测试工具。它可以导入 Spring Boot 的 OpenAPI，
将接口拖入画布组成线性业务流程，提取响应变量并传递给后续接口，最后生成执行报告。

## 当前能力

- 从 `/v3/api-docs` URL 或 OpenAPI 3 JSON/YAML 文件导入接口。
- 按 Tag 浏览和搜索 HTTP API。
- 使用 React Flow 拖拽、连接和保存线性流程。
- 配置 path/query/header/form/JSON 请求、认证和超时。
- Body 支持 JSON、x-www-form-urlencoded、Text 和 none；OpenAPI 有 requestBody 时默认生成 JSON 模板。
- 从 JSONPath、Header、Cookie、状态码提取运行变量。
- 断言状态码、JSONPath、Header 和响应时间。
- 使用 SQLite 持久化项目、环境、流程和运行记录。
- 对 Authorization、Cookie、password、token 等敏感数据脱敏。
- 导出单次运行的 JSON 报告。

## 本地构建

需要 Java 17 和 Node.js 20+，Maven 会由 Wrapper 自动准备。仓库根目录执行：

```powershell
.\build.ps1
```

启动：

```powershell
.\run.ps1
```

macOS/Linux 使用对应的 `./build.sh` 和 `./run.sh`。

服务默认绑定 `127.0.0.1:51780`，启动成功后自动打开浏览器。也可以通过
`SERVER_PORT` 环境变量指定其他端口。
SQLite 默认保存到 `%USERPROFILE%\.aft-studio\aft.db`，可通过 `AFT_DATA_DIR` 修改。

开发前端时，固定后端端口后运行 Vite：

```powershell
$env:SERVER_PORT = "8080"
$env:AFT_OPEN_BROWSER = "false"
java -jar .\aft-server\target\aft-server-0.1.0-SNAPSHOT-exec.jar

cd .\aft-web
npm run dev
```

## 快速体验

1. 首次启动创建本地项目。
2. 导入 `examples/order-service-openapi.json`。
3. 将登录、创建订单、查询订单依次拖入画布。
4. 登录节点提取 `token = $.data.token`。
5. 创建订单节点使用 Bearer `${run.token}`，并提取 `orderId = $.data.id`。
6. 查询节点将 path 参数 `id` 设置为 `${run.orderId}`。
7. 保存并运行流程。

## Windows 应用目录

完成构建后执行：

```powershell
.\distribution\package-windows.ps1
```

输出位于 `distribution/output/AFT Studio`。该应用目录包含运行时，目标机器无需单独安装 Java。

## 模块

- `aft-domain`：纯 Java 领域模型。
- `aft-openapi`：OpenAPI 解析和接口标准化。
- `aft-engine`：纯 Java 流程验证及 HTTP 执行引擎。
- `aft-server`：Spring Boot REST API、SQLite 和静态资源。
- `aft-web`：React、TypeScript 和 React Flow 工作台。
