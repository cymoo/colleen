// ============================================
// TERMINAL.CHAT — Client Application
// ============================================

class ChatClient {
    constructor() {
        this.ws = null;
        this.username = '';
        this.displayName = '';
        this.roomId = null;
        this.roomName = '';
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        
        this.init();
    }
    
    init() {
        this.bindEvents();
        this.loadRooms();
    }
    
    bindEvents() {
        // Login form
        document.getElementById('login-form').addEventListener('submit', (e) => {
            e.preventDefault();
            this.handleLogin();
        });
        
        // Message input
        const messageInput = document.getElementById('message-input');
        messageInput.addEventListener('keypress', (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });
        
        // Send button
        document.getElementById('send-btn').addEventListener('click', () => {
            this.sendMessage();
        });
        
        // Image upload
        document.getElementById('image-upload').addEventListener('change', (e) => {
            if (e.target.files[0]) {
                this.uploadImage(e.target.files[0]);
                e.target.value = '';
            }
        });
        
        // File upload
        document.getElementById('file-upload').addEventListener('change', (e) => {
            if (e.target.files[0]) {
                this.uploadFile(e.target.files[0]);
                e.target.value = '';
            }
        });
        
        // Disconnect button
        document.getElementById('disconnect-btn').addEventListener('click', () => {
            this.disconnect();
        });
        
        // Image modal
        const modal = document.getElementById('image-modal');
        const modalClose = document.getElementById('modal-close-btn');
        const backdrop = modal.querySelector('.modal-backdrop');
        
        modalClose.addEventListener('click', () => {
            modal.classList.remove('active');
        });
        
        backdrop.addEventListener('click', () => {
            modal.classList.remove('active');
        });
    }
    
    async loadRooms() {
        try {
            const response = await fetch('/api/rooms');
            const rooms = await response.json();
            
            const select = document.getElementById('room-select');
            select.innerHTML = rooms.map(room => 
                `<option value="${room.id}">${room.name.toUpperCase()} — ${room.onlineUsers}/${room.maxUsers} online</option>`
            ).join('');
        } catch (error) {
            console.error('Failed to load rooms:', error);
            this.showToast('Failed to load rooms', 'error');
        }
    }
    
    handleLogin() {
        this.username = document.getElementById('username-input').value.trim();
        this.displayName = document.getElementById('displayname-input').value.trim() || this.username;
        this.roomId = document.getElementById('room-select').value;
        
        if (!this.username || !this.roomId) {
            this.showToast('Please fill in all fields', 'error');
            return;
        }
        
        const select = document.getElementById('room-select');
        this.roomName = select.options[select.selectedIndex].text.split(' —')[0];
        
        this.connect();
    }
    
    connect() {
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/chat/${this.roomId}?username=${encodeURIComponent(this.username)}&displayName=${encodeURIComponent(this.displayName)}`;
        
        this.ws = new WebSocket(wsUrl);
        
        this.ws.onopen = () => {
            console.log('Connected to chat room');
            this.onConnected();
        };
        
        this.ws.onmessage = (event) => {
            this.handleMessage(JSON.parse(event.data));
        };
        
        this.ws.onclose = () => {
            console.log('Disconnected from chat room');
            this.onDisconnected();
        };
        
        this.ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            this.showToast('Connection error', 'error');
        };
    }
    
    onConnected() {
        this.reconnectAttempts = 0;
        
        // Switch screens
        document.getElementById('login-screen').classList.remove('active');
        document.getElementById('chat-screen').classList.add('active');
        
        // Update UI
        document.getElementById('current-room-name').textContent = this.roomName;
        document.getElementById('current-username').textContent = this.username;
        
        // Focus message input
        document.getElementById('message-input').focus();
        
        this.showToast('Connected to ' + this.roomName, 'success');
    }
    
    onDisconnected() {
        if (this.reconnectAttempts < this.maxReconnectAttempts) {
            this.reconnectAttempts++;
            setTimeout(() => {
                console.log(`Reconnecting... attempt ${this.reconnectAttempts}`);
                this.connect();
            }, 2000 * this.reconnectAttempts);
        } else {
            this.showToast('Connection lost. Please refresh.', 'error');
        }
    }
    
    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
        
        // Switch screens
        document.getElementById('chat-screen').classList.remove('active');
        document.getElementById('login-screen').classList.add('active');
        
        // Clear messages
        document.getElementById('messages-container').innerHTML = '';
        document.getElementById('users-list').innerHTML = '';
        
        this.showToast('Disconnected', 'success');
    }
    
    handleMessage(data) {
        switch (data.type) {
            case 'history':
                this.renderHistory(data.messages);
                break;
            case 'users':
                this.updateUsersList(data.users);
                break;
            case 'message':
                this.appendMessage(data.message);
                break;
            case 'user_joined':
                this.handleUserJoined(data.user);
                break;
            case 'user_left':
                this.handleUserLeft(data.userId);
                break;
            case 'error':
                this.showToast(data.message, 'error');
                break;
        }
    }
    
    renderHistory(messages) {
        const container = document.getElementById('messages-container');
        container.innerHTML = '';
        messages.forEach(msg => this.appendMessage(msg, false));
        this.scrollToBottom();
    }
    
    appendMessage(message, scroll = true) {
        const container = document.getElementById('messages-container');
        const messageEl = this.createMessageElement(message);
        container.appendChild(messageEl);
        
        if (scroll) {
            this.scrollToBottom();
        }
    }

    createMessageElement(message) {
        const div = document.createElement('div');
        const displayName = message.displayName || message.username || 'Unknown';

        // messageType 缺失时根据字段推断，兜底为 system
        let messageType = message.messageType;
        if (!messageType) {
            if (message.imageUrl) messageType = 'image';
            else if (message.fileUrl) messageType = 'file';
            else if (message.userId) messageType = 'text';
            else messageType = 'system';
        }

        if (messageType === 'system') {
            div.className = 'message system';
            div.innerHTML = `
            <div class="message-content">
                <div class="message-text">${this.escapeHtml(message.content || '')}</div>
            </div>
        `;
        } else {
            div.className = 'message';
            const avatar = this.getAvatar(displayName);
            const time = this.formatTime(message.timestamp);

            let contentHtml = '';
            if (messageType === 'text') {
                contentHtml = `<div class="message-text">${this.escapeHtml(message.content || '')}</div>`;
            } else if (messageType === 'image') {
                contentHtml = `
                <div class="message-text">shared an image</div>
                <img src="${message.imageUrl}" class="message-image" onclick="chatClient.openImageModal('${message.imageUrl}')">
            `;
            } else if (messageType === 'file') {
                contentHtml = `
                <div class="message-text">shared a file</div>
                <div class="message-file">
                    <div class="file-icon">
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
                            <polyline points="13 2 13 9 20 9"></polyline>
                        </svg>
                    </div>
                    <div class="file-info">
                        <div class="file-name">${this.escapeHtml(message.fileName || '')}</div>
                        <div class="file-size">${this.formatFileSize(message.fileSize || 0)}</div>
                    </div>
                    <a href="${message.fileUrl}" download class="file-download">DOWNLOAD</a>
                </div>
            `;
            }

            div.innerHTML = `
            <div class="message-avatar">${avatar}</div>
            <div class="message-content">
                <div class="message-header">
                    <div class="message-author">${this.escapeHtml(displayName)}</div>
                    <div class="message-time">${time}</div>
                </div>
                ${contentHtml}
            </div>
        `;
        }

        return div;
    }
    
    updateUsersList(users) {
        const container = document.getElementById('users-list');
        const count = users.length;
        
        document.getElementById('online-count').textContent = count;
        document.getElementById('users-count').textContent = count;
        
        container.innerHTML = users.map(user => {
            const avatar = this.getAvatar(user.displayName);
            return `
                <div class="user-item">
                    <div class="user-item-avatar">${avatar}</div>
                    <div class="user-item-info">
                        <div class="user-item-name">${this.escapeHtml(user.displayName)}</div>
                        <div class="user-item-username">@${this.escapeHtml(user.username)}</div>
                    </div>
                </div>
            `;
        }).join('');
    }
    
    handleUserJoined(user) {
        this.loadUsersList();
    }
    
    handleUserLeft(userId) {
        this.loadUsersList();
    }
    
    async loadUsersList() {
        // Users list is updated via WebSocket events
    }
    
    sendMessage() {
        const input = document.getElementById('message-input');
        const content = input.value.trim();
        
        if (!content || !this.ws) return;
        
        const payload = {
            type: 'text',
            content: content
        };
        
        this.ws.send(JSON.stringify(payload));
        input.value = '';
    }
    
    async uploadImage(file) {
        if (!this.ws) return;
        
        const formData = new FormData();
        formData.append('image', file);
        
        try {
            const response = await fetch('/api/upload/image', {
                method: 'POST',
                body: formData
            });
            
            const result = await response.json();
            
            if (result.success) {
                const payload = {
                    type: 'image',
                    imageUrl: result.url,
                    thumbnailUrl: result.thumbnail
                };
                this.ws.send(JSON.stringify(payload));
                this.showToast('Image uploaded', 'success');
            } else {
                this.showToast(result.error || 'Upload failed', 'error');
            }
        } catch (error) {
            console.error('Upload error:', error);
            this.showToast('Upload failed', 'error');
        }
    }
    
    async uploadFile(file) {
        if (!this.ws) return;
        
        const formData = new FormData();
        formData.append('file', file);
        
        try {
            const response = await fetch('/api/upload/file', {
                method: 'POST',
                body: formData
            });
            
            const result = await response.json();
            
            if (result.success) {
                const payload = {
                    type: 'file',
                    fileName: result.fileName,
                    fileUrl: result.url,
                    fileSize: result.fileSize,
                    mimeType: file.type
                };
                this.ws.send(JSON.stringify(payload));
                this.showToast('File uploaded', 'success');
            } else {
                this.showToast(result.error || 'Upload failed', 'error');
            }
        } catch (error) {
            console.error('Upload error:', error);
            this.showToast('Upload failed', 'error');
        }
    }
    
    openImageModal(imageUrl) {
        const modal = document.getElementById('image-modal');
        const img = document.getElementById('modal-image');
        img.src = imageUrl;
        modal.classList.add('active');
    }
    
    scrollToBottom() {
        const container = document.getElementById('messages-container');
        container.scrollTop = container.scrollHeight;
    }
    
    showToast(message, type = 'success') {
        const container = document.getElementById('toast-container');
        const toast = document.createElement('div');
        toast.className = `toast ${type}`;
        toast.textContent = message;
        
        container.appendChild(toast);
        
        setTimeout(() => {
            toast.style.opacity = '0';
            setTimeout(() => toast.remove(), 300);
        }, 3000);
    }
    
    getAvatar(name) {
        return name.charAt(0).toUpperCase();
    }
    
    formatTime(timestamp) {
        const date = new Date(timestamp);
        const hours = date.getHours().toString().padStart(2, '0');
        const minutes = date.getMinutes().toString().padStart(2, '0');
        return `${hours}:${minutes}`;
    }
    
    formatFileSize(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    }
    
    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

// Initialize chat client
const chatClient = new ChatClient();
