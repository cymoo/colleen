# Jooq with Coleen and SQLite

This is an example project demonstrating how to integrate the Colleen framework with JOOQ (code generation) and an
SQLite database.

## Project Structure

```
src/
├── main/
│   ├── kotlin/
│   │   └── com/example/
│   │       ├── config/
│   │       │   └── DatabaseConfig.kt       # Database configuration
│   │       ├── controller/
│   │       │   ├── UserController.kt       # User controller
│   │       │   └── PostController.kt       # Post controller
│   │       ├── repository/
│   │       │   ├── UserRepository.kt       # User repository
│   │       │   └── PostRepository.kt       # Post repository
│   │       └── Model.kt                    # Domain models
│   │       └── Main.kt                     # Application entry point
│   └── resources/
│       └── schema.sql                      # Database schema
├── pom.xml                                 # Maven configuration
└── demo.db                                 # SQLite database (auto-generated)
```

## Core Features

- ✅ **JOOQ Auto Code Generation** - Based on database schema
- ✅ **Connection Pool** - Based on HikariCP
- ✅ **Type-Safe SQL Queries** - Compile-time checking
- ✅ **Transaction Support** - Full transaction management
- ✅ **JOIN Queries** - Multi-table join examples
- ✅ **RESTful API** - Complete CRUD operations
- ✅ **Middleware System** - Logging, CORS
- ✅ **Path & Query Parameters** - Flexible routing
- ✅ **Dependency Injection** - Clean architecture
- ✅ **Domain Models** - Separation from JOOQ POJOs

## Build and Run

### 1. Generate JOOQ Code

Run Maven to generate JOOQ code:

```bash
mvn clean generate-sources
```

This will:

- Create SQLite database file `demo.db`
- Execute `schema.sql` to create tables and sample data
- Generate JOOQ code to `target/generated-sources/jooq` directory

### 2. Compile Project

```bash
mvn compile
```

### 3. Run Application

```bash
mvn exec:java -Dexec.mainClass="MainKt"
```

Or run `Main.kt` directly in your IDE.

Server will start at `http://localhost:8000`.

## API Endpoints

### User API

| Method | Endpoint     | Description     |
|--------|--------------|-----------------|
| GET    | `/users`     | Get all users   |
| GET    | `/users/:id` | Get user by ID  |
| POST   | `/users`     | Create new user |
| DELETE | `/users/:id` | Delete user     |

### Post API

| Method | Endpoint                | Description               |
|--------|-------------------------|---------------------------|
| GET    | `/posts`                | Get all posts             |
| GET    | `/posts?published=true` | Get published posts       |
| GET    | `/posts/:id`            | Get post with author info |
| POST   | `/posts`                | Create new post           |
| PUT    | `/posts/:id`            | Update post               |
| DELETE | `/posts/:id`            | Delete post               |
| GET    | `/posts/users/:userId`  | Get user's posts          |

## Example Requests

### Get All Users

```bash
curl http://localhost:8000/users
```

### Get User by ID

```bash
curl http://localhost:8000/users/1
```

### Create New User

```bash
curl -X POST http://localhost:8000/users \
  -H "Content-Type: application/json" \
  -d '{"username":"david","email":"david@example.com"}'
```

### Get All Posts

```bash
curl http://localhost:8000/posts
```

### Get Published Posts

```bash
curl http://localhost:8000/posts?published=true
```

### Get Post with Author

```bash
curl http://localhost:8000/posts/1
```

### Create New Post

```bash
curl -X POST http://localhost:8000/posts \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"title":"New Post","content":"Content here","published":true}'
```

### Update Post

```bash
curl -X PUT http://localhost:8000/posts/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"Updated Title","published":true}'
```

### Delete Post

```bash
curl -X DELETE http://localhost:8000/posts/1
```

### Get User's Posts

```bash
curl http://localhost:8000/posts/users/1
```