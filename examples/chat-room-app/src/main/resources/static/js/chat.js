// ============================================
// TERMINAL.CHAT — Client Application
// ============================================

class ChatClient {
    constructor() {
        this.ws = null;
        this.username = '';
        this.displayName = '';
        this.userId = null;
        this.roomId = null;
        this.roomName = '';
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.intentionalDisconnect = false;
        this.users = [];
        this.oldestMessageId = null;
        this.hasMoreHistory = true;
        this.loadingHistory = false;
        this.editingMessageId = null;
        this.replyingTo = null;
        this.mentionDropdownVisible = false;
        this.privateChatUserId = null;
        this.privateChatUsername = '';
        this.searchVisible = false;

        this.init();
    }
    
    init() {
        this.configureMarkdown();
        this.bindEvents();
        this.loadRooms();
    }

    configureMarkdown() {
        if (typeof marked !== 'undefined') {
            marked.setOptions({
                gfm: true,
                breaks: true,
                highlight: function(code, lang) {
                    if (typeof hljs !== 'undefined' && lang && hljs.getLanguage(lang)) {
                        try { return hljs.highlight(code, { language: lang }).value; } catch (e) {}
                    }
                    return code;
                }
            });
        }
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

        messageInput.addEventListener('input', (e) => {
            this.handleMentionInput(e);
        });

        messageInput.addEventListener('keydown', (e) => {
            this.handleMentionKeydown(e);
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

        // Search
        document.getElementById('search-toggle-btn').addEventListener('click', () => {
            this.toggleSearch();
        });
        document.getElementById('search-close-btn').addEventListener('click', () => {
            this.toggleSearch();
        });
        document.getElementById('search-input').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                this.performSearch();
            }
        });

        // Indicator cancel (reply/edit)
        document.getElementById('indicator-cancel').addEventListener('click', () => {
            this.cancelIndicator();
        });

        // Private message input
        document.getElementById('private-message-input').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') {
                this.sendPrivateMessage();
            }
        });

        // Scroll to load history
        const messagesContainer = document.getElementById('messages-container');
        messagesContainer.addEventListener('scroll', () => {
            if (messagesContainer.scrollTop < 50 && this.hasMoreHistory && !this.loadingHistory && this.oldestMessageId) {
                this.loadMoreHistory();
            }
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
        this.intentionalDisconnect = false;
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
        if (this.intentionalDisconnect) {
            return;
        }
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
        this.intentionalDisconnect = true;
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
        this.users = [];
        this.oldestMessageId = null;
        this.hasMoreHistory = true;
        
        this.showToast('Disconnected', 'success');
    }
    
    handleMessage(data) {
        switch (data.type) {
            case 'history':
                if (this.loadingHistory) {
                    this.prependHistory(data.messages, data.hasMore);
                } else {
                    this.renderHistory(data.messages, data.hasMore);
                }
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
            case 'message_edited':
                this.handleMessageEdited(data.messageId, data.content, data.editedAt);
                break;
            case 'message_deleted':
                this.handleMessageDeleted(data.messageId);
                break;
            case 'private_message':
                this.handlePrivateMessageReceived(data.message);
                break;
            case 'private_history':
                this.renderPrivateHistory(data.messages, data.hasMore);
                break;
            case 'mention':
                this.handleMention(data);
                break;
            case 'user_updated':
                this.handleUserUpdated(data.user);
                break;
            case 'kicked':
                this.handleKicked(data.reason);
                break;
            case 'role_changed':
                this.handleRoleChanged(data.userId, data.role);
                break;
            case 'search_results':
                this.renderSearchResults(data.messages, data.query);
                break;
        }
    }
    
    // ============ History & Messages ============
    
    renderHistory(messages, hasMore = false) {
        const container = document.getElementById('messages-container');
        container.innerHTML = '';
        this.hasMoreHistory = hasMore;
        if (messages.length > 0) {
            this.oldestMessageId = messages[0].id;
        }
        messages.forEach(msg => this.appendMessage(msg, false));
        this.scrollToBottom();
    }

    prependHistory(messages, hasMore) {
        this.loadingHistory = false;
        this.hasMoreHistory = hasMore;
        document.getElementById('history-loader').style.display = 'none';

        if (messages.length === 0) return;

        const container = document.getElementById('messages-container');
        const previousScrollHeight = container.scrollHeight;
        
        this.oldestMessageId = messages[0].id;
        
        const fragment = document.createDocumentFragment();
        messages.forEach(msg => {
            fragment.appendChild(this.createMessageElement(msg));
        });
        container.insertBefore(fragment, container.firstChild);

        // Maintain scroll position
        container.scrollTop = container.scrollHeight - previousScrollHeight;
    }

    loadMoreHistory() {
        if (!this.ws || !this.oldestMessageId || this.loadingHistory) return;
        this.loadingHistory = true;
        document.getElementById('history-loader').style.display = 'flex';
        
        this.ws.send(JSON.stringify({
            type: 'load_history',
            beforeId: this.oldestMessageId
        }));
    }
    
    appendMessage(message, scroll = true) {
        const container = document.getElementById('messages-container');
        const messageEl = this.createMessageElement(message);
        container.appendChild(messageEl);

        // Track oldest message ID
        if (!this.oldestMessageId || message.id < this.oldestMessageId) {
            this.oldestMessageId = message.id;
        }
        
        if (scroll) {
            this.scrollToBottom();
        }
    }

    renderMarkdown(text) {
        if (typeof marked === 'undefined' || typeof DOMPurify === 'undefined') {
            return this.escapeHtml(text);
        }
        try {
            const raw = marked.parse(text);
            return DOMPurify.sanitize(raw);
        } catch (e) {
            return this.escapeHtml(text);
        }
    }

    highlightMentions(html) {
        return html.replace(/@(\w+)/g, (match, username) => {
            const isSelf = username === this.username;
            const safeUsername = this.escapeAttr(username);
            return `<span class="mention-highlight${isSelf ? ' mention-self' : ''}" onclick="chatClient.openUserProfileByName('${safeUsername}')">${this.escapeHtml(match)}</span>`;
        });
    }

    createMessageElement(message) {
        const div = document.createElement('div');
        const displayName = message.displayName || message.username || 'Unknown';
        const isOwn = message.username === this.username;

        // messageType fallback
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
            div.setAttribute('data-message-id', message.id);
            const avatar = this.getAvatar(displayName);
            const time = this.formatTime(message.timestamp);
            const editedHtml = message.editedAt ? '<span class="edited-tag">(edited)</span>' : '';

            // Reply preview
            let replyHtml = '';
            if (message.replyTo) {
                const r = message.replyTo;
                replyHtml = `
                <div class="reply-preview" onclick="chatClient.scrollToMessage(${r.id})">
                    <span class="reply-author">↩ ${this.escapeHtml(r.displayName)}</span>
                    <span class="reply-content">${this.escapeHtml(r.content)}</span>
                </div>`;
            }

            let contentHtml = '';
            if (messageType === 'text') {
                let rendered = this.renderMarkdown(message.content || '');
                rendered = this.highlightMentions(rendered);
                contentHtml = `<div class="message-text markdown-body">${rendered} ${editedHtml}</div>`;
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

            // Action buttons use data attributes instead of inline scripts for safety
            const actionsHtml = `
                <div class="message-actions">
                    <button class="msg-action-btn msg-action-reply" data-msg-id="${message.id}" data-display-name="${this.escapeAttr(displayName)}" data-preview="${this.escapeAttr((message.content || '').substring(0, 50))}" title="Reply">↩</button>
                    ${isOwn && messageType === 'text' ? `<button class="msg-action-btn msg-action-edit" data-msg-id="${message.id}" data-content="${this.escapeAttr(message.content || '')}" title="Edit">✎</button>` : ''}
                    ${isOwn ? `<button class="msg-action-btn msg-action-delete" data-msg-id="${message.id}" title="Delete">✕</button>` : ''}
                </div>
            `;

            div.innerHTML = `
            <div class="message-avatar">${message.avatarUrl ? `<img src="${this.escapeAttr(message.avatarUrl)}" alt="" class="avatar-img">` : avatar}</div>
            <div class="message-content">
                ${replyHtml}
                <div class="message-header">
                    <div class="message-author">${this.escapeHtml(displayName)}</div>
                    <div class="message-time">${time}</div>
                    ${actionsHtml}
                </div>
                ${contentHtml}
            </div>
        `;

            // Attach event listeners safely
            const replyBtn = div.querySelector('.msg-action-reply');
            if (replyBtn) {
                replyBtn.addEventListener('click', () => {
                    this.startReply(message.id, displayName, (message.content || '').substring(0, 50));
                });
            }
            const editBtn = div.querySelector('.msg-action-edit');
            if (editBtn) {
                editBtn.addEventListener('click', () => {
                    this.startEdit(message.id, message.content || '');
                });
            }
            const deleteBtn = div.querySelector('.msg-action-delete');
            if (deleteBtn) {
                deleteBtn.addEventListener('click', () => {
                    this.confirmDelete(message.id);
                });
            }
        }

        return div;
    }

    // ============ User List ============
    
    updateUsersList(users) {
        this.users = users;
        // Try to find self userId
        const self = users.find(u => u.username === this.username);
        if (self) this.userId = self.id;
        this.renderUsersList();
    }

    renderUsersList() {
        const container = document.getElementById('users-list');
        const count = this.users.length;
        
        document.getElementById('online-count').textContent = count;
        document.getElementById('users-count').textContent = count;
        
        container.innerHTML = '';
        this.users.forEach(user => {
            const avatar = this.getAvatar(user.displayName);
            const roleHtml = user.role && user.role !== 'member' ?
                `<span class="role-badge role-${this.escapeHtml(user.role)}">${this.escapeHtml(user.role.toUpperCase())}</span>` : '';
            const statusHtml = user.status ?
                `<div class="user-item-status">${this.escapeHtml(user.status)}</div>` : '';
            
            const itemDiv = document.createElement('div');
            itemDiv.className = 'user-item';
            itemDiv.setAttribute('data-user-id', user.id);
            itemDiv.innerHTML = `
                <div class="user-item-avatar">${user.avatarUrl ? `<img src="${this.escapeAttr(user.avatarUrl)}" alt="" class="avatar-img">` : avatar}</div>
                <div class="user-item-info">
                    <div class="user-item-name">${this.escapeHtml(user.displayName)} ${roleHtml}</div>
                    <div class="user-item-username">@${this.escapeHtml(user.username)}</div>
                    ${statusHtml}
                </div>
                <button class="dm-btn" title="Send DM">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                    </svg>
                </button>
            `;
            
            // Safe event binding
            itemDiv.addEventListener('click', () => this.openUserProfile(user.id));
            const dmBtn = itemDiv.querySelector('.dm-btn');
            dmBtn.addEventListener('click', (e) => {
                e.stopPropagation();
                this.openPrivateChat(user.id, user.username, user.displayName);
            });
            
            container.appendChild(itemDiv);
        });
    }
    
    handleUserJoined(user) {
        if (!this.users.find(u => u.id === user.id)) {
            this.users.push(user);
        }
        this.renderUsersList();
    }
    
    handleUserLeft(userId) {
        this.users = this.users.filter(u => u.id !== userId);
        this.renderUsersList();
    }
    
    // ============ Sending Messages ============
    
    sendMessage() {
        const input = document.getElementById('message-input');
        const content = input.value.trim();
        
        if (!content || !this.ws) return;

        if (this.editingMessageId) {
            this.ws.send(JSON.stringify({
                type: 'edit',
                messageId: this.editingMessageId,
                content: content
            }));
            this.cancelIndicator();
        } else {
            const payload = {
                type: 'text',
                content: content
            };
            if (this.replyingTo) {
                payload.replyToId = this.replyingTo.id;
            }
            this.ws.send(JSON.stringify(payload));
            this.cancelIndicator();
        }
        
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
                if (this.replyingTo) {
                    payload.replyToId = this.replyingTo.id;
                    this.cancelIndicator();
                }
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
                if (this.replyingTo) {
                    payload.replyToId = this.replyingTo.id;
                    this.cancelIndicator();
                }
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

    // ============ Edit / Delete ============

    startEdit(messageId, content) {
        this.editingMessageId = messageId;
        this.replyingTo = null;
        document.getElementById('message-input').value = content;
        document.getElementById('message-input').focus();
        document.getElementById('input-indicator').style.display = 'flex';
        document.getElementById('indicator-text').textContent = '✎ Editing message...';
    }

    confirmDelete(messageId) {
        if (confirm('Delete this message?')) {
            this.ws.send(JSON.stringify({ type: 'delete', messageId }));
        }
    }

    handleMessageEdited(messageId, content, editedAt) {
        const el = document.querySelector(`[data-message-id="${messageId}"] .message-text`);
        if (el) {
            let rendered = this.renderMarkdown(content);
            rendered = this.highlightMentions(rendered);
            el.innerHTML = rendered + ' <span class="edited-tag">(edited)</span>';
        }
    }

    handleMessageDeleted(messageId) {
        const el = document.querySelector(`[data-message-id="${messageId}"]`);
        if (el) {
            el.classList.add('message-deleted');
            const textEl = el.querySelector('.message-text');
            if (textEl) {
                textEl.innerHTML = '<em class="deleted-text">This message has been deleted</em>';
            }
            const actions = el.querySelector('.message-actions');
            if (actions) actions.remove();
        }
    }

    // ============ Reply ============

    startReply(messageId, displayName, preview) {
        this.replyingTo = { id: messageId, displayName, preview };
        this.editingMessageId = null;
        document.getElementById('input-indicator').style.display = 'flex';
        document.getElementById('indicator-text').textContent = `↩ Replying to ${displayName}: ${preview}...`;
        document.getElementById('message-input').focus();
    }

    cancelIndicator() {
        this.editingMessageId = null;
        this.replyingTo = null;
        document.getElementById('input-indicator').style.display = 'none';
    }

    scrollToMessage(messageId) {
        const el = document.querySelector(`[data-message-id="${messageId}"]`);
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            el.classList.add('message-highlight');
            setTimeout(() => el.classList.remove('message-highlight'), 2000);
        }
    }

    // ============ Mentions ============

    handleMentionInput(e) {
        const input = e.target;
        const value = input.value;
        const cursorPos = input.selectionStart;
        
        // Find @ before cursor
        const textBefore = value.substring(0, cursorPos);
        const atMatch = textBefore.match(/@(\w*)$/);
        
        if (atMatch) {
            const query = atMatch[1].toLowerCase();
            const matches = this.users.filter(u => 
                u.username.toLowerCase().startsWith(query) ||
                u.displayName.toLowerCase().startsWith(query)
            ).slice(0, 5);
            
            if (matches.length > 0) {
                this.showMentionDropdown(matches, atMatch[0].length);
                return;
            }
        }
        this.hideMentionDropdown();
    }

    handleMentionKeydown(e) {
        if (!this.mentionDropdownVisible) return;
        
        const dropdown = document.getElementById('mention-dropdown');
        const items = dropdown.querySelectorAll('.mention-item');
        const selected = dropdown.querySelector('.mention-item.selected');
        
        if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
            e.preventDefault();
            let idx = Array.from(items).indexOf(selected);
            if (selected) selected.classList.remove('selected');
            idx = e.key === 'ArrowDown' ? (idx + 1) % items.length : (idx - 1 + items.length) % items.length;
            items[idx].classList.add('selected');
        } else if (e.key === 'Enter' && selected) {
            e.preventDefault();
            this.insertMention(selected.dataset.username);
        } else if (e.key === 'Escape') {
            this.hideMentionDropdown();
        }
    }

    showMentionDropdown(users, matchLen) {
        const dropdown = document.getElementById('mention-dropdown');
        dropdown.innerHTML = '';
        users.forEach((u, i) => {
            const item = document.createElement('div');
            item.className = `mention-item${i === 0 ? ' selected' : ''}`;
            item.setAttribute('data-username', u.username);
            item.innerHTML = `
                <span class="mention-item-name">${this.escapeHtml(u.displayName)}</span>
                <span class="mention-item-username">@${this.escapeHtml(u.username)}</span>
            `;
            item.addEventListener('click', () => this.insertMention(u.username));
            dropdown.appendChild(item);
        });
        dropdown.style.display = 'block';
        this.mentionDropdownVisible = true;
    }

    hideMentionDropdown() {
        document.getElementById('mention-dropdown').style.display = 'none';
        this.mentionDropdownVisible = false;
    }

    insertMention(username) {
        const input = document.getElementById('message-input');
        const value = input.value;
        const cursorPos = input.selectionStart;
        const textBefore = value.substring(0, cursorPos);
        const textAfter = value.substring(cursorPos);
        const newBefore = textBefore.replace(/@\w*$/, `@${username} `);
        input.value = newBefore + textAfter;
        input.selectionStart = input.selectionEnd = newBefore.length;
        input.focus();
        this.hideMentionDropdown();
    }

    handleMention(data) {
        this.showToast(`${data.mentionedBy} mentioned you`, 'success');
        // Browser notification
        if (Notification.permission === 'granted') {
            new Notification('TERMINAL.CHAT', { body: `${data.mentionedBy} mentioned you` });
        } else if (Notification.permission !== 'denied') {
            Notification.requestPermission();
        }
    }

    // ============ Search ============

    toggleSearch() {
        this.searchVisible = !this.searchVisible;
        const bar = document.getElementById('search-bar');
        bar.style.display = this.searchVisible ? 'block' : 'none';
        if (this.searchVisible) {
            document.getElementById('search-input').focus();
        } else {
            document.getElementById('search-results').style.display = 'none';
        }
    }

    performSearch() {
        const query = document.getElementById('search-input').value.trim();
        if (!query || !this.ws) return;
        this.ws.send(JSON.stringify({ type: 'search', query }));
    }

    renderSearchResults(messages, query) {
        const container = document.getElementById('search-results');
        container.style.display = 'block';
        if (messages.length === 0) {
            container.innerHTML = '<div class="search-no-results">No messages found</div>';
            return;
        }
        container.innerHTML = '';
        messages.forEach(msg => {
            const name = msg.displayName || msg.username || 'Unknown';
            const content = msg.content || '';
            const time = this.formatTime(msg.timestamp);
            // Escape content first, then highlight query matches
            const escaped = this.escapeHtml(content);
            const highlighted = escaped.replace(
                new RegExp(`(${this.escapeRegex(this.escapeHtml(query))})`, 'gi'),
                '<mark class="search-highlight">$1</mark>'
            );
            const item = document.createElement('div');
            item.className = 'search-result-item';
            item.innerHTML = `
                <div class="search-result-header">
                    <span class="search-result-author">${this.escapeHtml(name)}</span>
                    <span class="search-result-time">${time}</span>
                </div>
                <div class="search-result-content">${highlighted}</div>
            `;
            item.addEventListener('click', () => {
                this.scrollToMessage(msg.id);
                this.toggleSearch();
            });
            container.appendChild(item);
        });
    }

    // ============ Private Messages ============

    openPrivateChat(userId, username, displayName) {
        this.privateChatUserId = userId;
        this.privateChatUsername = username;
        document.getElementById('private-chat-title').textContent = `DM: ${displayName}`;
        document.getElementById('private-chat-messages').innerHTML = '<div class="loading-text">Loading...</div>';
        document.getElementById('private-chat-modal').classList.add('active');

        // Request history
        if (this.ws) {
            this.ws.send(JSON.stringify({
                type: 'private_history',
                targetUserId: userId
            }));
        }
    }

    closePrivateChat() {
        document.getElementById('private-chat-modal').classList.remove('active');
        this.privateChatUserId = null;
    }

    sendPrivateMessage() {
        const input = document.getElementById('private-message-input');
        const content = input.value.trim();
        if (!content || !this.ws || !this.privateChatUserId) return;

        this.ws.send(JSON.stringify({
            type: 'private_message',
            targetUserId: this.privateChatUserId,
            content: content
        }));
        input.value = '';
    }

    handlePrivateMessageReceived(message) {
        // If private chat is open with this user, append message
        if (this.privateChatUserId === message.senderId || this.privateChatUserId === message.receiverId) {
            this.appendPrivateChatMessage(message);
        } else {
            // Show notification
            this.showToast(`DM from ${message.senderDisplayName}: ${(message.content || '').substring(0, 50)}`, 'success');
        }
    }

    renderPrivateHistory(messages, hasMore) {
        const container = document.getElementById('private-chat-messages');
        container.innerHTML = '';
        messages.forEach(msg => this.appendPrivateChatMessage(msg));
        container.scrollTop = container.scrollHeight;
    }

    appendPrivateChatMessage(message) {
        const container = document.getElementById('private-chat-messages');
        const loadingEl = container.querySelector('.loading-text');
        if (loadingEl) loadingEl.remove();

        const isSelf = message.senderUsername === this.username;
        const div = document.createElement('div');
        div.className = `private-msg ${isSelf ? 'private-msg-self' : 'private-msg-other'}`;
        div.innerHTML = `
            <div class="private-msg-author">${this.escapeHtml(message.senderDisplayName)}</div>
            <div class="private-msg-content">${this.renderMarkdown(message.content || '')}</div>
            <div class="private-msg-time">${this.formatTime(message.timestamp)}</div>
        `;
        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
    }

    // ============ User Profiles ============

    async openUserProfile(userId) {
        try {
            const response = await fetch(`/api/users/${userId}`);
            const user = await response.json();
            
            const content = document.getElementById('user-profile-content');
            content.innerHTML = `
                <div class="profile-avatar-large">${user.avatarUrl ? `<img src="${this.escapeAttr(user.avatarUrl)}" class="avatar-img-large">` : this.getAvatar(user.displayName)}</div>
                <div class="profile-display-name">${this.escapeHtml(user.displayName)}</div>
                <div class="profile-username">@${this.escapeHtml(user.username)}</div>
                ${user.status ? `<div class="profile-status">${this.escapeHtml(user.status)}</div>` : ''}
                ${user.bio ? `<div class="profile-bio">${this.escapeHtml(user.bio)}</div>` : ''}
                <div class="profile-joined">Joined: ${new Date(user.createdAt).toLocaleDateString()}</div>
                <div class="profile-actions">
                    <button class="profile-action-btn profile-dm-btn">Send DM</button>
                    <button class="profile-action-btn profile-mention-btn">@Mention</button>
                </div>
            `;
            // Safe event binding
            content.querySelector('.profile-dm-btn').addEventListener('click', () => {
                this.closeUserProfile();
                this.openPrivateChat(user.id, user.username, user.displayName);
            });
            content.querySelector('.profile-mention-btn').addEventListener('click', () => {
                this.insertMentionInChat(user.username);
            });
            document.getElementById('user-profile-modal').classList.add('active');
        } catch (e) {
            this.showToast('Failed to load profile', 'error');
        }
    }

    openUserProfileByName(username) {
        const user = this.users.find(u => u.username === username);
        if (user) this.openUserProfile(user.id);
    }

    closeUserProfile() {
        document.getElementById('user-profile-modal').classList.remove('active');
    }

    insertMentionInChat(username) {
        this.closeUserProfile();
        const input = document.getElementById('message-input');
        input.value += `@${username} `;
        input.focus();
    }

    handleUserUpdated(user) {
        const idx = this.users.findIndex(u => u.id === user.id);
        if (idx !== -1) {
            this.users[idx] = user;
            this.renderUsersList();
        }
    }

    // ============ Room Permissions ============

    handleKicked(reason) {
        this.showToast(`You were kicked: ${reason}`, 'error');
        this.disconnect();
    }

    handleRoleChanged(userId, role) {
        const user = this.users.find(u => u.id === userId);
        if (user) {
            user.role = role;
            this.renderUsersList();
        }
        if (userId === this.userId) {
            this.showToast(`Your role changed to: ${role}`, 'success');
        }
    }

    // ============ Utilities ============
    
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

    escapeAttr(text) {
        return text.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"');
    }

    escapeRegex(str) {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }
}

// Initialize chat client
const chatClient = new ChatClient();
