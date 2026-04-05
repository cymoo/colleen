// ============================================
// CHAT API — HTTP Requests and Authentication
// ============================================

Object.assign(ChatClient.prototype, {

    // ============ API Helper ============

    async apiRequest(method, url, body) {
        const headers = {};
        if (this.authToken) {
            headers['Authorization'] = `Bearer ${this.authToken}`;
        }
        if (body !== undefined) {
            headers['Content-Type'] = 'application/json';
        }
        const options = { method, headers };
        if (body !== undefined) {
            options.body = JSON.stringify(body);
        }
        const response = await fetch(url, options);
        if (response.status === 204) return null;
        return response.json();
    },

    // ============ Auth ============

    async checkAuth() {
        if (!this.authToken) {
            this.showAuthScreen();
            return;
        }
        try {
            const user = await this.apiRequest('GET', '/api/auth/me');
            if (user && user.id) {
                this.currentUser = user;
                localStorage.setItem('currentUser', JSON.stringify(user));
                this.username = user.username;
                this.showLobbyScreen();
            } else {
                this.clearAuth();
                this.showAuthScreen();
            }
        } catch (e) {
            this.clearAuth();
            this.showAuthScreen();
        }
    },

    clearAuth() {
        this.authToken = null;
        this.currentUser = null;
        localStorage.removeItem('authToken');
        localStorage.removeItem('currentUser');
    },

    async handleLogin(e) {
        e.preventDefault();
        const username = document.getElementById('login-username').value.trim();
        const password = document.getElementById('login-password').value;
        if (!username || !password) {
            this.showToast('Please fill in all fields', 'error');
            return;
        }
        try {
            document.getElementById('auth-status-text').textContent = 'AUTHENTICATING...';
            const data = await this.apiRequest('POST', '/api/auth/login', { username, password });
            if (data && data.token) {
                this.authToken = data.token;
                this.currentUser = data.user;
                localStorage.setItem('authToken', data.token);
                localStorage.setItem('currentUser', JSON.stringify(data.user));
                this.username = data.user.username;
                this.showToast('Welcome back, ' + data.user.username, 'success');
                this.showLobbyScreen();
            } else {
                this.showToast((data && data.message) || 'Login failed', 'error');
            }
        } catch (e) {
            this.showToast('Login failed', 'error');
        } finally {
            document.getElementById('auth-status-text').textContent = 'SYSTEM READY';
        }
    },

    async handleRegister(e) {
        e.preventDefault();
        const username = document.getElementById('register-username').value.trim();
        const password = document.getElementById('register-password').value;
        const confirm = document.getElementById('register-confirm').value;
        if (!username || !password) {
            this.showToast('Please fill in all fields', 'error');
            return;
        }
        if (password !== confirm) {
            this.showToast('Passwords do not match', 'error');
            return;
        }
        try {
            document.getElementById('auth-status-text').textContent = 'REGISTERING...';
            const data = await this.apiRequest('POST', '/api/auth/register', { username, password });
            if (data && data.token) {
                this.authToken = data.token;
                this.currentUser = data.user;
                localStorage.setItem('authToken', data.token);
                localStorage.setItem('currentUser', JSON.stringify(data.user));
                this.username = data.user.username;
                this.showToast('Welcome, ' + data.user.username, 'success');
                this.showLobbyScreen();
            } else {
                this.showToast((data && data.message) || 'Registration failed', 'error');
            }
        } catch (e) {
            this.showToast('Registration failed', 'error');
        } finally {
            document.getElementById('auth-status-text').textContent = 'SYSTEM READY';
        }
    },

    async logout() {
        try {
            await this.apiRequest('POST', '/api/auth/logout');
        } catch (e) {}
        if (this.ws) {
            this.intentionalDisconnect = true;
            this.ws.close();
            this.ws = null;
        }
        this.clearAuth();
        this.username = '';
        this.showAuthScreen();
        this.showToast('Logged out', 'success');
    },

    // ============ Lobby: Rooms ============

    async loadRoomsForLobby() {
        const grid = document.getElementById('lobby-rooms-grid');
        grid.innerHTML = '<div class="lobby-loading">Loading rooms...</div>';
        try {
            const rooms = await this.apiRequest('GET', '/api/rooms');
            this.rooms = rooms;
            this.renderRooms(rooms);
        } catch (error) {
            grid.innerHTML = '<div class="lobby-loading">Failed to load rooms</div>';
            this.showToast('Failed to load rooms', 'error');
        }
    },

    renderRooms(rooms) {
        const grid = document.getElementById('lobby-rooms-grid');
        if (!rooms || rooms.length === 0) {
            grid.innerHTML = '<div class="lobby-loading">No rooms available. Create one!</div>';
            return;
        }
        grid.innerHTML = '';
        rooms.forEach(room => {
            const card = document.createElement('div');
            card.className = 'room-card';
            card.setAttribute('data-room-id', room.id);
            const lockIcon = room.isPrivate ? '<span class="room-lock-icon">&#x1F512;</span>' : '';
            const onlineCount = room.onlineUsers !== undefined ? room.onlineUsers : 0;
            const maxUsers = room.maxUsers || '\u221E';
            card.innerHTML = `
                <div class="room-card-header">
                    <div class="room-card-name">${this.escapeHtml(room.name.toUpperCase())} ${lockIcon}</div>
                </div>
                ${room.description ? `<div class="room-card-desc">${this.escapeHtml(room.description)}</div>` : ''}
                <div class="room-card-meta">
                    <span class="pulse-dot"></span>
                    <span>${onlineCount}/${maxUsers} ONLINE</span>
                </div>
            `;
            card.addEventListener('click', () => this.joinRoom(room));
            grid.appendChild(card);
        });
    },

    joinRoom(room) {
        if (room.isPrivate) {
            this.openRoomPasswordDialog(room);
        } else {
            this.connectToRoom(room.id, room.name, '');
        }
    },

    connectToRoom(roomId, roomName, roomPassword) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.currentRoomPassword = roomPassword;
        this.connect();
    },

    // ============ Room Password Dialog ============

    openRoomPasswordDialog(room) {
        this._pendingRoom = room;
        document.getElementById('room-password-title').textContent = '\uD83D\uDD12 ' + room.name.toUpperCase();
        document.getElementById('room-password-input').value = '';
        document.getElementById('room-password-modal').classList.add('active');
        setTimeout(() => document.getElementById('room-password-input').focus(), 100);
    },

    closeRoomPasswordDialog() {
        document.getElementById('room-password-modal').classList.remove('active');
        this._pendingRoom = null;
    },

    submitRoomPassword() {
        const password = document.getElementById('room-password-input').value;
        if (!password) {
            this.showToast('Please enter the room password', 'error');
            return;
        }
        const room = this._pendingRoom;
        this.closeRoomPasswordDialog();
        this.connectToRoom(room.id, room.name, password);
    },

    // ============ Create Room Modal ============

    openCreateRoomModal() {
        document.getElementById('create-room-name').value = '';
        document.getElementById('create-room-desc').value = '';
        document.getElementById('create-room-password').value = '';
        document.querySelector('input[name="room-visibility"][value="public"]').checked = true;
        document.getElementById('room-password-group').style.display = 'none';
        document.getElementById('create-room-modal').classList.add('active');
    },

    closeCreateRoomModal() {
        document.getElementById('create-room-modal').classList.remove('active');
    },

    async createRoom() {
        const name = document.getElementById('create-room-name').value.trim();
        const description = document.getElementById('create-room-desc').value.trim();
        const isPrivate = document.querySelector('input[name="room-visibility"]:checked').value === 'private';
        const password = document.getElementById('create-room-password').value;
        if (!name) {
            this.showToast('Room name is required', 'error');
            return;
        }
        if (isPrivate && !password) {
            this.showToast('Private rooms require a password', 'error');
            return;
        }
        try {
            const body = { name, description, isPrivate };
            if (isPrivate) body.password = password;
            const room = await this.apiRequest('POST', '/api/rooms', body);
            if (room && room.id) {
                this.showToast('Room created: ' + room.name, 'success');
                this.closeCreateRoomModal();
                this.loadRoomsForLobby();
            } else {
                this.showToast((room && room.message) || 'Failed to create room', 'error');
            }
        } catch (e) {
            this.showToast('Failed to create room', 'error');
        }
    },

    // ============ My Profile Modal ============

    openMyProfile() {
        if (!this.currentUser) return;
        this._pendingAvatarUrl = this.currentUser.avatarUrl || null;
        this.populateMyProfile();
        document.getElementById('my-profile-modal').classList.add('active');
    },

    populateMyProfile() {
        const user = this.currentUser;
        if (!user) return;
        const avatarEl = document.getElementById('my-profile-avatar');
        if (this._pendingAvatarUrl) {
            avatarEl.innerHTML = `<img src="${this.escapeAttr(this._pendingAvatarUrl)}" class="avatar-img-large" alt="">`;
        } else {
            avatarEl.textContent = this.getAvatar(user.username || '?');
        }
        document.getElementById('my-profile-status').value = user.status || '';
        document.getElementById('my-profile-bio').value = user.bio || '';
        document.getElementById('my-profile-username').value = user.username || '';
    },

    closeMyProfile() {
        document.getElementById('my-profile-modal').classList.remove('active');
        this._pendingAvatarUrl = null;
    },

    async uploadAvatar(file) {
        const formData = new FormData();
        formData.append('image', file);
        try {
            const response = await fetch('/api/upload/image', {
                method: 'POST',
                headers: { 'Authorization': `Bearer ${this.authToken || ''}` },
                body: formData
            });
            const result = await response.json();
            if (result.url) {
                this._pendingAvatarUrl = result.url;
                const avatarEl = document.getElementById('my-profile-avatar');
                avatarEl.innerHTML = `<img src="${this.escapeAttr(result.url)}" class="avatar-img-large" alt="">`;
                this.showToast('Avatar uploaded', 'success');
            } else {
                this.showToast(result.error || 'Upload failed', 'error');
            }
        } catch (e) {
            this.showToast('Avatar upload failed', 'error');
        }
    },

    saveProfile() {
        const status = document.getElementById('my-profile-status').value.trim();
        const bio = document.getElementById('my-profile-bio').value.trim();
        const avatarUrl = this._pendingAvatarUrl || (this.currentUser && this.currentUser.avatarUrl) || '';

        this.apiRequest('PATCH', '/api/users/me', { status, bio, avatarUrl })
            .then(updatedUser => {
                this.currentUser = updatedUser;
                localStorage.setItem('currentUser', JSON.stringify(updatedUser));
                this._pendingAvatarUrl = null;
                this.showToast('Profile updated', 'success');
                document.getElementById('my-profile-modal').classList.remove('active');
            })
            .catch(err => this.showToast(err.message || 'Failed to update profile', 'error'));

        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({ type: 'update_profile', status, bio, avatarUrl }));
        }
    }
});
