// ============================================
// CHAT MESSAGES — Rendering, History, Edit/Delete, Reply
// ============================================

Object.assign(ChatClient.prototype, {

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
    },

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
        container.scrollTop = container.scrollHeight - previousScrollHeight;
    },

    loadMoreHistory() {
        if (!this.ws || !this.oldestMessageId || this.loadingHistory) return;
        this.loadingHistory = true;
        document.getElementById('history-loader').style.display = 'flex';
        this.ws.send(JSON.stringify({
            type: 'load_history',
            beforeId: this.oldestMessageId
        }));
    },

    appendMessage(message, scroll = true) {
        const container = document.getElementById('messages-container');
        const messageEl = this.createMessageElement(message);
        container.appendChild(messageEl);
        if (!this.oldestMessageId || message.id < this.oldestMessageId) {
            this.oldestMessageId = message.id;
        }
        if (scroll) {
            this.scrollToBottom();
        }
    },

    createMessageElement(message) {
        const div = document.createElement('div');
        const username = message.username || 'Unknown';
        const isOwn = message.username === this.username;

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
            const avatar = this.getAvatar(username);
            const time = this.formatTime(message.timestamp);
            const editedHtml = message.editedAt ? '<span class="edited-tag">(edited)</span>' : '';

            let replyHtml = '';
            if (message.replyTo) {
                const r = message.replyTo;
                replyHtml = `
                    <div class="reply-preview" data-reply-id="${r.id}">
                        <span class="reply-author">↩ ${this.escapeHtml(r.username)}</span>
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

            const actionsHtml = `
                <div class="message-actions">
                    <button class="msg-action-btn msg-action-reply" title="Reply">↩</button>
                    ${isOwn && messageType === 'text' ? `<button class="msg-action-btn msg-action-edit" title="Edit">✎</button>` : ''}
                    ${isOwn ? `<button class="msg-action-btn msg-action-delete" title="Delete">✕</button>` : ''}
                </div>
            `;

            div.innerHTML = `
                <div class="message-avatar" data-username="${this.escapeAttr(username)}" style="cursor:pointer;">${message.avatarUrl ? `<img src="${this.escapeAttr(message.avatarUrl)}" alt="" class="avatar-img">` : avatar}</div>
                <div class="message-content">
                    ${replyHtml}
                    <div class="message-header">
                        <div class="message-author">${this.escapeHtml(username)}</div>
                        <div class="message-time">${time}</div>
                        ${actionsHtml}
                    </div>
                    ${contentHtml}
                </div>
            `;

            div.querySelector('.msg-action-reply').addEventListener('click', () => {
                this.startReply(message.id, username, (message.content || '').substring(0, 50));
            });
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
    },

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
            const payload = { type: 'text', content: content };
            if (this.replyingTo) {
                payload.replyToId = this.replyingTo.id;
            }
            this.ws.send(JSON.stringify(payload));
            this.cancelIndicator();
        }

        input.value = '';
        this.autoResizeInput(input);
    },

    autoResizeInput(input) {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    },

    async uploadImage(file) {
        if (!this.ws) return;
        const formData = new FormData();
        formData.append('image', file);
        try {
            const response = await fetch('/api/upload/image', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${this.authToken || ''}` },
                body: formData
            });
            const result = await response.json();
            if (result.success) {
                const payload = { type: 'image', imageUrl: result.url, thumbnailUrl: result.thumbnail };
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
            this.showToast('Upload failed', 'error');
        }
    },

    async uploadFile(file) {
        if (!this.ws) return;
        const formData = new FormData();
        formData.append('file', file);
        try {
            const response = await fetch('/api/upload/file', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${this.authToken || ''}` },
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
            this.showToast('Upload failed', 'error');
        }
    },

    // ============ Edit / Delete ============

    startEdit(messageId, content) {
        this.editingMessageId = messageId;
        this.replyingTo = null;
        const input = document.getElementById('message-input');
        input.value = content;
        input.focus();
        this.autoResizeInput(input);
        document.getElementById('input-indicator').style.display = 'flex';
        document.getElementById('indicator-text').innerHTML = '✎ Editing message...';
    },

    confirmDelete(messageId) {
        if (confirm('Delete this message?')) {
            this.ws.send(JSON.stringify({ type: 'delete', messageId }));
        }
    },

    handleMessageEdited(messageId, content, editedAt) {
        const el = document.querySelector(`[data-message-id="${messageId}"] .message-text`);
        if (el) {
            let rendered = this.renderMarkdown(content);
            rendered = this.highlightMentions(rendered);
            el.innerHTML = rendered + ' <span class="edited-tag">(edited)</span>';
        }
    },

    handleMessageDeleted(messageId) {
        const el = document.querySelector(`[data-message-id="${messageId}"]`);
        if (el) el.remove();
    },

    // ============ Reply ============

    startReply(messageId, username, preview) {
        this.replyingTo = { id: messageId, username, preview };
        this.editingMessageId = null;
        document.getElementById('input-indicator').style.display = 'flex';
        document.getElementById('indicator-text').innerHTML = `↩ Replying to ${this.escapeHtml(username)}: ${this.escapeHtml(preview)}...`;
        document.getElementById('message-input').focus();
    },

    cancelIndicator() {
        this.editingMessageId = null;
        this.replyingTo = null;
        document.getElementById('input-indicator').style.display = 'none';
    },

    scrollToMessage(messageId) {
        const el = document.querySelector(`[data-message-id="${messageId}"]`);
        if (el) {
            el.scrollIntoView({ behavior: 'smooth', block: 'center' });
            el.classList.add('message-highlight');
            setTimeout(() => el.classList.remove('message-highlight'), 2000);
        }
    }
});
