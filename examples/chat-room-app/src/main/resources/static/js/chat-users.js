// ============================================
// CHAT USERS — User List, Profiles, Private Messages
// ============================================

Object.assign(ChatClient.prototype, {

    // ============ User List ============

    updateUsersList(users) {
        this.users = users;
        const self = users.find(u => u.username === this.username);
        if (self) this.userId = self.id;
        this.renderUsersList();
    },

    renderUsersList() {
        const container = document.getElementById('users-list');
        const count = this.users.length;
        document.getElementById('online-count').textContent = count;
        document.getElementById('users-count').textContent = count;
        container.innerHTML = '';

        this.users.forEach(user => {
            const avatar = this.getAvatar(user.username);
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
                    <div class="user-item-name">${this.escapeHtml(user.username)} ${roleHtml}</div>
                    ${statusHtml}
                </div>
                <button class="dm-btn" title="Send DM">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
                    </svg>
                </button>
            `;

            itemDiv.addEventListener('click', () => this.openUserProfile(user.id));
            itemDiv.querySelector('.dm-btn').addEventListener('click', (e) => {
                e.stopPropagation();
                this.openPrivateChat(user.id, user.username);
            });

            container.appendChild(itemDiv);
        });
    },

    handleUserJoined(user) {
        if (!this.users.find(u => u.id === user.id)) {
            this.users.push(user);
        }
        this.renderUsersList();
    },

    handleUserLeft(userId) {
        this.users = this.users.filter(u => u.id !== userId);
        this.renderUsersList();
    },

    handleUserUpdated(user) {
        const idx = this.users.findIndex(u => u.id === user.id);
        if (idx !== -1) {
            this.users[idx] = user;
            this.renderUsersList();
        }
        if (this.currentUser && user.id === this.currentUser.id) {
            this.currentUser = Object.assign({}, this.currentUser, user);
            localStorage.setItem('currentUser', JSON.stringify(this.currentUser));
            this.username = this.currentUser.username;
            const usernameEl = document.getElementById('current-username');
            if (usernameEl) usernameEl.textContent = this.currentUser.username;
            const lobbyUsernameEl = document.getElementById('lobby-username');
            if (lobbyUsernameEl) lobbyUsernameEl.textContent = this.currentUser.username;
            const myProfileModal = document.getElementById('my-profile-modal');
            if (myProfileModal && myProfileModal.classList.contains('active')) {
                this._pendingAvatarUrl = this.currentUser.avatarUrl || null;
                this.populateMyProfile();
            }
        }
    },

    // ============ User Profile Modal ============

    async openUserProfile(userId) {
        const safeUserId = parseInt(userId, 10);
        if (isNaN(safeUserId)) return;
        try {
            const response = await fetch(`/api/users/${safeUserId}`);
            const user = await response.json();
            this._openUserProfileData(user);
        } catch (e) {
            this.showToast('Failed to load profile', 'error');
        }
    },

    _openUserProfileData(user) {
        const content = document.getElementById('user-profile-content');
        const isOnline = this.users.some(u => u.id === user.id);
        content.innerHTML = `
            <div class="profile-avatar-large">${user.avatarUrl ? `<img src="${this.escapeAttr(user.avatarUrl)}" class="avatar-img-large">` : this.getAvatar(user.username)}</div>
            <div class="profile-display-name">${this.escapeHtml(user.username)}</div>
            ${isOnline ? '<div class="profile-online-badge">● ONLINE</div>' : ''}
            ${user.status ? `<div class="profile-status">${this.escapeHtml(user.status)}</div>` : ''}
            ${user.bio ? `<div class="profile-bio">${this.escapeHtml(user.bio)}</div>` : ''}
            <div class="profile-joined">Joined: ${new Date(user.createdAt).toLocaleDateString()}</div>
            <div class="profile-actions">
                <button class="profile-action-btn profile-dm-btn">Private Message</button>
                <button class="profile-action-btn profile-mention-btn">@Mention</button>
            </div>
        `;
        content.querySelector('.profile-dm-btn').addEventListener('click', () => {
            this.closeUserProfile();
            this.openPrivateChat(user.id, user.username);
        });
        content.querySelector('.profile-mention-btn').addEventListener('click', () => {
            this.insertMentionInChat(user.username);
        });
        document.getElementById('user-profile-modal').classList.add('active');
    },

    openUserProfileByName(username) {
        const user = this.users.find(u => u.username === username);
        if (user) {
            this.openUserProfile(user.id);
        } else {
            // User is offline — look up via API
            fetch(`/api/users/by-name/${encodeURIComponent(username)}`)
                .then(r => r.ok ? r.json() : null)
                .then(u => {
                    if (u) this._openUserProfileData(u);
                    else this.showToast('User not found', 'error');
                })
                .catch(() => this.showToast('Failed to load profile', 'error'));
        }
    },

    closeUserProfile() {
        document.getElementById('user-profile-modal').classList.remove('active');
    },

    // ============ Private Messages ============

    openPrivateChat(userId, username) {
        this.privateChatUserId = userId;
        this.privateChatUsername = username;
        document.getElementById('private-chat-title').textContent = `DM: ${username}`;
        document.getElementById('private-chat-messages').innerHTML = '<div class="loading-text">Loading...</div>';
        document.getElementById('private-chat-modal').classList.add('active');

        if (this.ws) {
            this.ws.send(JSON.stringify({
                type: 'private_history',
                targetUserId: userId
            }));
        }
    },

    closePrivateChat() {
        document.getElementById('private-chat-modal').classList.remove('active');
        this.privateChatUserId = null;
    },

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
    },

    handlePrivateMessageReceived(message) {
        if (this.privateChatUserId === message.senderId || this.privateChatUserId === message.receiverId) {
            this.appendPrivateChatMessage(message);
        } else {
            this.showToast(`DM from ${message.senderUsername}: ${(message.content || '').substring(0, 50)}`, 'success');
        }
    },

    renderPrivateHistory(messages, hasMore) {
        const container = document.getElementById('private-chat-messages');
        container.innerHTML = '';
        messages.forEach(msg => this.appendPrivateChatMessage(msg));
        container.scrollTop = container.scrollHeight;
    },

    appendPrivateChatMessage(message) {
        const container = document.getElementById('private-chat-messages');
        const loadingEl = container.querySelector('.loading-text');
        if (loadingEl) loadingEl.remove();

        const isSelf = message.senderUsername === this.username;
        const div = document.createElement('div');
        div.className = `private-msg ${isSelf ? 'private-msg-self' : 'private-msg-other'}`;
        div.innerHTML = `
            <div class="private-msg-author">${this.escapeHtml(message.senderUsername)}</div>
            <div class="private-msg-content">${this.renderMarkdown(message.content || '')}</div>
            <div class="private-msg-time">${this.formatTime(message.timestamp)}</div>
        `;
        container.appendChild(div);
        container.scrollTop = container.scrollHeight;
    },

    // ============ Room Permissions ============

    handleKicked(reason) {
        this.showToast(`You were kicked: ${reason}`, 'error');
        this.disconnect();
    },

    handleRoleChanged(userId, role) {
        const user = this.users.find(u => u.id === userId);
        if (user) {
            user.role = role;
            this.renderUsersList();
        }
        if (userId === this.userId) {
            this.showToast(`Your role changed to: ${role}`, 'success');
        }
    },

    // ============ DM Inbox ============

    async openDmInbox() {
        const modal = document.getElementById('dm-inbox-modal');
        const list = document.getElementById('dm-inbox-list');
        if (!modal) return;
        list.innerHTML = '<div class="loading-text">Loading...</div>';
        modal.classList.add('active');
        try {
            const res = await fetch('/api/dms/unread-senders', {
                headers: { 'Authorization': `Bearer ${this.authToken}` }
            });
            const senders = await res.json();
            list.innerHTML = '';
            if (senders.length === 0) {
                list.innerHTML = '<div class="dm-inbox-empty">No unread messages</div>';
                return;
            }
            senders.forEach(s => {
                const avatar = s.avatarUrl
                    ? `<img src="${this.escapeAttr(s.avatarUrl)}" class="avatar-img" alt="">`
                    : this.getAvatar(s.username);
                const item = document.createElement('div');
                item.className = 'dm-inbox-item';
                item.innerHTML = `
                    <div class="dm-inbox-avatar">${avatar}</div>
                    <div class="dm-inbox-name">${this.escapeHtml(s.username)}</div>
                    <div class="dm-inbox-badge">${s.unreadCount}</div>
                `;
                item.addEventListener('click', () => {
                    modal.classList.remove('active');
                    this.openPrivateChat(s.userId, s.username);
                });
                list.appendChild(item);
            });
        } catch (e) {
            list.innerHTML = '<div class="dm-inbox-empty">Failed to load</div>';
        }
    }
});
