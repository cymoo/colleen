# TERMINAL.CHAT — Real-time Chat Room Application

A feature-rich, real-time chat room application built with the Colleen framework, showcasing WebSocket communication, file uploads, and database integration.

## Features

### Backend
- **WebSocket Communication**: Real-time bidirectional messaging
- **SQLite + Flyway**: Database migrations and schema management
- **jOOQ**: Type-safe database queries with code generation
- **File Uploads**: Support for images (5MB max) and files (20MB max)
- **Multiple Chat Rooms**: Support for multiple concurrent chat rooms
- **User Management**: Automatic user creation and tracking
- **Message History**: Persistent message storage with 100 message limit per room
- **Online Status**: Real-time tracking of online users

### Frontend
- **Distinctive Terminal Aesthetic**: Brutalist design with green terminal theme
- **Real-time Updates**: Instant message delivery and user presence
- **Image Sharing**: Upload and preview images in chat
- **File Sharing**: Upload and download files with metadata
- **Responsive Design**: Works on desktop and mobile
- **Toast Notifications**: User-friendly feedback system
- **Image Modal**: Full-size image preview
- **Smooth Animations**: Terminal-style transitions and effects

## Technology Stack

- **Framework**: Colleen 0.4.3
- **Language**: Kotlin
- **Database**: SQLite
- **Migrations**: Flyway
- **ORM**: jOOQ with code generation
- **WebSocket**: Built-in Colleen WebSocket support
- **JSON**: Jackson (integrated with Colleen)
- **Frontend**: Vanilla JavaScript + Custom CSS

## Project Structure

```
chat-room-app/
├── src/main/
│   ├── kotlin/
│   │   ├── config/
│   │   │   └── DatabaseConfig.kt      # Database configuration and Flyway setup
│   │   ├── model/
│   │   │   └── Models.kt              # Data models and DTOs
│   │   ├── repository/
│   │   │   └── Repositories.kt        # jOOQ database repositories
│   │   ├── service/
│   │   │   └── Services.kt            # Business logic services
│   │   ├── controller/
│   │   │   └── Controllers.kt         # REST and WebSocket controllers
│   │   ├── middleware/
│   │   │   └── WsAuthMiddleware.kt    # WebSocket authentication
│   │   └── Main.kt                    # Application entry point
│   └── resources/
│       ├── db/migration/
│       │   └── V1__Create_initial_schema.sql  # Database schema
│       └── static/
│           ├── index.html             # Frontend HTML
│           ├── css/
│           │   └── style.css          # Distinctive terminal styling
│           └── js/
│               └── chat.js            # Client-side logic
├── uploads/                           # File upload directory
├── chat.db                           # SQLite database (auto-created)
└── pom.xml                           # Maven configuration
```

## Getting Started

### Prerequisites

- Java 21 or higher
- Maven 3.6+

### Build and Run

1. **Build the project** (this will run Flyway migrations and generate jOOQ code):

```bash
cd examples/chat-room-app
mvn clean install
```

2. **Run the application**:

```bash
mvn exec:java
```

3. **Open your browser**:

```
http://localhost:8000
```

### Database Management

The application uses Flyway for database migrations. The schema is automatically created on first run.

To manually run migrations:

```bash
mvn flyway:migrate
```

To regenerate jOOQ code after schema changes:

```bash
mvn clean generate-sources
```

## Usage

1. **Login**:
   - Enter a username (required)
   - Optionally set a display name
   - Select a chat room (general, random, or tech)
   - Click "CONNECT"

2. **Chat**:
   - Type messages in the input field
   - Press Enter to send
   - Use Shift+Enter for multi-line messages

3. **Share Images**:
   - Click the image icon
   - Select an image file (JPEG, PNG, GIF, WebP)
   - Maximum size: 5MB

4. **Share Files**:
   - Click the file icon
   - Select any file
   - Maximum size: 20MB

5. **View Users**:
   - Online users are shown in the right sidebar
   - Real-time updates when users join/leave

## API Endpoints

### REST API

- `GET /api/rooms` - Get all available chat rooms
- `POST /api/rooms` - Create a new chat room
- `POST /api/upload/image` - Upload an image
- `POST /api/upload/file` - Upload a file
- `GET /uploads/{filename}` - Download/view uploaded files

### WebSocket

- `WS /chat/{roomId}?username={username}&displayName={displayName}` - Connect to a chat room

#### WebSocket Message Types (Client → Server)

```json
// Text message
{
  "type": "text",
  "content": "Hello world"
}

// Image message (after upload)
{
  "type": "image",
  "imageUrl": "/uploads/image.jpg",
  "thumbnailUrl": null
}

// File message (after upload)
{
  "type": "file",
  "fileName": "document.pdf",
  "fileUrl": "/uploads/document.pdf",
  "fileSize": 1024000,
  "mimeType": "application/pdf"
}
```

#### WebSocket Events (Server → Client)

```json
// Message history
{
  "type": "history",
  "messages": [...]
}

// Users list
{
  "type": "users",
  "users": [...]
}

// New message
{
  "type": "message",
  "message": {...}
}

// User joined
{
  "type": "user_joined",
  "user": {...}
}

// User left
{
  "type": "user_left",
  "userId": 123,
  "username": "user1"
}

// Error
{
  "type": "error",
  "message": "Error description"
}
```

## Configuration

### WebSocket Configuration

Edit `Main.kt` to customize WebSocket settings:

```kotlin
app.config {
    ws {
        idleTimeoutMs = 600_000          // 10 minutes
        maxMessageSizeBytes = 256 * 1024 // 256 KB
        pingIntervalMs = 30_000          // 30 seconds
        pingTimeoutMs = 10_000           // 10 seconds
        maxConnections = 500             // Max connections
    }
}
```

### File Upload Limits

Edit `service/Services.kt` to change upload limits:

```kotlin
private val maxImageSize: Long = 5 * 1024 * 1024  // 5MB
private val maxFileSize: Long = 20 * 1024 * 1024  // 20MB
```

## Design Philosophy

The frontend follows a **brutalist terminal aesthetic** with these characteristics:

- **Monospace Typography**: IBM Plex Mono for all text, Bebas Neue for headers
- **Terminal Green Theme**: Bright green (#00ff41) on dark backgrounds
- **Glitch Effects**: Subtle digital artifacts and scanning animations
- **Harsh Borders**: Sharp 2px borders with bright green accents
- **Noise Overlay**: Subtle film grain for texture
- **No Gradients**: Flat colors and high contrast
- **Functional Layout**: Grid-based, utilitarian structure
- **Scanline Animation**: CRT monitor effect on login screen

This creates a distinctive, memorable interface that stands out from generic chat applications.

## Future Enhancements

Potential features to add:

- **Private Messages**: Direct messaging between users
- **User Mentions**: @username notifications
- **Message Reactions**: Emoji reactions to messages
- **Message Editing/Deletion**: Modify or remove sent messages
- **Rich Text**: Markdown or code block support
- **Search**: Search message history
- **User Profiles**: Avatars, bios, status messages
- **Room Permissions**: Admin/moderator roles
- **Message Threading**: Reply to specific messages
- **Voice/Video**: WebRTC integration
- **Redis**: For scalable multi-instance deployment
- **PostgreSQL**: For production-grade persistence

## License

This example is part of the Colleen framework examples and follows the same license.
