# Colleen PostgreSQL + jOOQ Template

English | [中文](#中文)

This example is a project template for building a Colleen web application with:

- PostgreSQL
- HikariCP connection pooling
- Flyway migrations
- jOOQ runtime DSL and code generation
- Environment-based configuration with `APP_ENV` and `.env.xxx` files
- Colleen controllers, middleware, validation, and OpenAPI docs
- Testcontainers-backed integration tests

## Project layout

```text
src/main/kotlin/com/example/app/
  Application.kt          # App assembly and entry point
  config/                 # APP_ENV and .env loading, typed config
  db/                     # Hikari, Flyway, jOOQ setup
  controller/             # Colleen controllers
  model/                  # DTOs and response models
  repository/             # jOOQ queries
  service/                # Business rules
src/main/java/com/example/jooq/generated/
  ...                     # Initial committed jOOQ generated code
src/main/resources/db/migration/
  V1__create_users.sql
  V2__seed_users.sql
```

## Configuration

Runtime config is loaded in this order:

1. `.env`
2. `.env.${APP_ENV}`
3. System environment variables

`APP_ENV` defaults to `development`. Production is strict: with `APP_ENV=production`, database connection settings and credentials must come from system environment variables, not committed env files.

The template supports `DATABASE_URL` first. If it is absent, it builds the JDBC URL from `DB_HOST`, `DB_PORT`, and `DB_NAME`.

## Local development

Start PostgreSQL:

```shell
cd examples/postgres-jooq
docker compose up -d
```

Run the app from the repository root:

```shell
mvn -pl examples/postgres-jooq -am exec:java
```

Endpoints:

- `GET /health` - process liveness, does not query PostgreSQL
- `GET /ready` - readiness, runs `select 1`
- `GET /users/`
- `GET /users/{id}`
- `POST /users/`
- `PUT /users/{id}`
- `DELETE /users/{id}`
- `GET /openapi.json`
- `GET /docs`

## Refresh jOOQ generated code

The initial generated Java classes are committed so the normal examples build does not require a running PostgreSQL instance.

After changing Flyway migrations, start PostgreSQL and run:

```shell
mvn -pl examples/postgres-jooq -Pcodegen generate-sources
```

To generate against another environment:

```shell
APP_ENV=test mvn -pl examples/postgres-jooq -Pcodegen generate-sources
```

You can also override values with system environment variables or Maven properties used by the profile.

## External PostgreSQL

Set these variables instead of using `docker-compose.yml`:

```shell
export APP_ENV=development
export DATABASE_URL='jdbc:postgresql://db.example.com:5432/colleen_app'
export DB_USER='app_user'
export DB_PASSWORD='change-me'
mvn -pl examples/postgres-jooq -am exec:java
```

For platform-style URLs, `postgres://user:password@host:5432/dbname` is also accepted at runtime.

## Tests

```shell
mvn -pl examples/postgres-jooq test
```

The integration test uses Testcontainers PostgreSQL and is skipped automatically when Docker is unavailable.

## Package and Docker image

Build the fat jar:

```shell
mvn -pl examples/postgres-jooq -am -DskipTests package
```

Build the Docker image from the repository root:

```shell
docker build -f examples/postgres-jooq/Dockerfile -t colleen-postgres-jooq .
```

Run the image with production configuration supplied by real environment variables:

```shell
docker run --rm -p 8000:8000 \
  -e APP_ENV=production \
  -e DATABASE_URL='jdbc:postgresql://host.docker.internal:5432/colleen_app' \
  -e DB_USER='colleen' \
  -e DB_PASSWORD='colleen' \
  colleen-postgres-jooq
```

---

## 中文

这是一个使用 Colleen 开发 Web 应用的项目模板，包含：

- PostgreSQL
- HikariCP 连接池
- Flyway 数据库迁移
- jOOQ DSL 与代码生成
- 基于 `APP_ENV` 和 `.env.xxx` 文件的配置
- Colleen Controller、中间件、校验和 OpenAPI 文档
- 基于 Testcontainers 的集成测试

## 项目结构

```text
src/main/kotlin/com/example/app/
  Application.kt          # 应用组装和入口
  config/                 # APP_ENV、.env 加载和类型化配置
  db/                     # Hikari、Flyway、jOOQ 初始化
  controller/             # Colleen 控制器
  model/                  # DTO 和响应模型
  repository/             # jOOQ 查询
  service/                # 业务规则
src/main/java/com/example/jooq/generated/
  ...                     # 已提交的初始 jOOQ 生成代码
src/main/resources/db/migration/
  V1__create_users.sql
  V2__seed_users.sql
```

## 配置规则

运行时配置按以下优先级加载：

1. `.env`
2. `.env.${APP_ENV}`
3. 系统环境变量

`APP_ENV` 默认是 `development`。生产环境更严格：当 `APP_ENV=production` 时，数据库连接和敏感凭据必须由系统环境变量提供，不能依赖提交到仓库的 env 文件。

模板优先使用 `DATABASE_URL`。如果没有设置，则通过 `DB_HOST`、`DB_PORT` 和 `DB_NAME` 组装 JDBC URL。

## 本地开发

启动 PostgreSQL：

```shell
cd examples/postgres-jooq
docker compose up -d
```

从仓库根目录运行应用：

```shell
mvn -pl examples/postgres-jooq -am exec:java
```

接口：

- `GET /health` - 进程存活检查，不访问 PostgreSQL
- `GET /ready` - 就绪检查，执行 `select 1`
- `GET /users/`
- `GET /users/{id}`
- `POST /users/`
- `PUT /users/{id}`
- `DELETE /users/{id}`
- `GET /openapi.json`
- `GET /docs`

## 刷新 jOOQ 生成代码

模板提交了一份初始 Java 生成代码，因此普通 examples 构建不需要本地 PostgreSQL。

修改 Flyway 迁移后，先启动 PostgreSQL，再运行：

```shell
mvn -pl examples/postgres-jooq -Pcodegen generate-sources
```

针对其他环境生成：

```shell
APP_ENV=test mvn -pl examples/postgres-jooq -Pcodegen generate-sources
```

也可以通过系统环境变量或 Maven profile 使用的属性覆盖连接配置。

## 外部 PostgreSQL

不使用 `docker-compose.yml` 时，设置以下变量：

```shell
export APP_ENV=development
export DATABASE_URL='jdbc:postgresql://db.example.com:5432/colleen_app'
export DB_USER='app_user'
export DB_PASSWORD='change-me'
mvn -pl examples/postgres-jooq -am exec:java
```

运行时也支持 `postgres://user:password@host:5432/dbname` 形式的 URL。

## 测试

```shell
mvn -pl examples/postgres-jooq test
```

集成测试使用 Testcontainers PostgreSQL；如果 Docker 不可用，会自动跳过。

## 打包和 Docker 镜像

构建 fat jar：

```shell
mvn -pl examples/postgres-jooq -am -DskipTests package
```

从仓库根目录构建 Docker 镜像：

```shell
docker build -f examples/postgres-jooq/Dockerfile -t colleen-postgres-jooq .
```

使用真实环境变量运行生产镜像：

```shell
docker run --rm -p 8000:8000 \
  -e APP_ENV=production \
  -e DATABASE_URL='jdbc:postgresql://host.docker.internal:5432/colleen_app' \
  -e DB_USER='colleen' \
  -e DB_PASSWORD='colleen' \
  colleen-postgres-jooq
```
