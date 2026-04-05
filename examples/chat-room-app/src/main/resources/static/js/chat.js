// ============================================
// TERMINAL.CHAT — Core Application
// ============================================

class ChatClient {
    constructor() {
        this.ws = null;
        this.username = '';
        this.userId = null;
        this.roomId = null;
        this.roomName = '';
        this.currentRoomPassword = '';
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.intentionalDisconnect = false;
        this.users = [];
        this.rooms = [];
        this.oldestMessageId = null;
        this.hasMoreHistory = true;
        this.loadingHistory = false;
        this.editingMessageId = null;
        this.replyingTo = null;
        this.mentionDropdownVisible = false;
        this.privateChatUserId = null;
        this.privateChatUsername = '';
        this.searchVisible = false;

        // Auth state
        this.authToken = localStorage.getItem('authToken');
        this.currentUser = JSON.parse(localStorage.getItem('currentUser') || 'null');

        // Pending state
        this._pendingRoom = null;
        this._pendingAvatarUrl = null;

        this.init();
    }

    init() {
        this.configureMarkdown();
        this.bindEvents();
        this.checkAuth();
    }

    // ============ Screen Management ============

    showAuthScreen() {
        document.getElementById('auth-screen').classList.add('active');
        document.getElementById('lobby-screen').classList.remove('active');
        document.getElementById('chat-screen').classList.remove('active');
    }

    showLobbyScreen() {
        document.getElementById('auth-screen').classList.remove('active');
        document.getElementById('lobby-screen').classList.add('active');
        document.getElementById('chat-screen').classList.remove('active');
        const name = (this.currentUser && this.currentUser.username) || 'USER';
        document.getElementById('lobby-username').textContent = name;
        this.loadRoomsForLobby();
    }

    showChatScreen() {
        document.getElementById('auth-screen').classList.remove('active');
        document.getElementById('lobby-screen').classList.remove('active');
        document.getElementById('chat-screen').classList.add('active');
    }

    // ============ Event Binding ============

    bindEvents() {
        // Auth tabs
        document.querySelectorAll('.auth-tab').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.auth-tab').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.auth-form').forEach(f => f.classList.remove('active'));
                tab.classList.add('active');
                document.getElementById(`${tab.dataset.tab}-form`).classList.add('active');
            });
        });

        document.getElementById('login-form').addEventListener('submit', (e) => this.handleLogin(e));
        document.getElementById('register-form').addEventListener('submit', (e) => this.handleRegister(e));

        // Lobby
        document.getElementById('lobby-logout-btn').addEventListener('click', () => this.logout());
        document.getElementById('create-room-btn').addEventListener('click', () => this.openCreateRoomModal());
        document.getElementById('my-profile-lobby-btn').addEventListener('click', () => this.openMyProfile());
        document.getElementById('my-profile-chat-btn').addEventListener('click', () => this.openMyProfile());

        // Create room modal
        document.getElementById('create-room-close-btn').addEventListener('click', () => this.closeCreateRoomModal());
        document.getElementById('create-room-backdrop').addEventListener('click', () => this.closeCreateRoomModal());
        document.getElementById('create-room-submit-btn').addEventListener('click', () => this.createRoom());
        document.querySelectorAll('input[name="room-visibility"]').forEach(radio => {
            radio.addEventListener('change', () => {
                document.getElementById('room-password-group').style.display =
                    (radio.value === 'private' && radio.checked) ? 'block' : 'none';
            });
        });

        // Room password modal
        document.getElementById('room-password-close-btn').addEventListener('click', () => this.closeRoomPasswordDialog());
        document.getElementById('room-password-backdrop').addEventListener('click', () => this.closeRoomPasswordDialog());
        document.getElementById('room-password-cancel-btn').addEventListener('click', () => this.closeRoomPasswordDialog());
        document.getElementById('room-password-join-btn').addEventListener('click', () => this.submitRoomPassword());
        document.getElementById('room-password-input').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.submitRoomPassword();
        });

        // My profile modal
        document.getElementById('my-profile-close-btn').addEventListener('click', () => this.closeMyProfile());
        document.getElementById('my-profile-backdrop').addEventListener('click', () => this.closeMyProfile());
        document.getElementById('my-profile-save-btn').addEventListener('click', () => this.saveProfile());
        document.getElementById('my-profile-avatar').addEventListener('click', () => {
            document.getElementById('avatar-upload').click();
        });
        document.getElementById('avatar-upload').addEventListener('change', (e) => {
            if (e.target.files[0]) {
                this.uploadAvatar(e.target.files[0]);
                e.target.value = '';
            }
        });

        // Message input (textarea)
        const messageInput = document.getElementById('message-input');
        messageInput.addEventListener('keydown', (e) => {
            this.handleMentionKeydown(e);
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                this.sendMessage();
            }
        });
        messageInput.addEventListener('input', (e) => {
            this.handleMentionInput(e);
            this.autoResizeInput(e.target);
        });

        // Send button
        document.getElementById('send-btn').addEventListener('click', () => this.sendMessage());

        // Image / file upload
        document.getElementById('image-upload').addEventListener('change', (e) => {
            if (e.target.files[0]) {
                this.uploadImage(e.target.files[0]);
                e.target.value = '';
            }
        });
        document.getElementById('file-upload').addEventListener('change', (e) => {
            if (e.target.files[0]) {
                this.uploadFile(e.target.files[0]);
                e.target.value = '';
            }
        });

        // Disconnect button
        document.getElementById('disconnect-btn').addEventListener('click', () => this.disconnect());

        // DM badge — open a panel listing unread DM senders
        document.getElementById('dm-badge-btn').addEventListener('click', () => this.openDmInbox());
        document.getElementById('dm-inbox-close-btn').addEventListener('click', () => document.getElementById('dm-inbox-modal').classList.remove('active'));
        document.getElementById('dm-inbox-backdrop').addEventListener('click', () => document.getElementById('dm-inbox-modal').classList.remove('active'));

        // Image modal
        const modal = document.getElementById('image-modal');
        document.getElementById('modal-close-btn').addEventListener('click', () => modal.classList.remove('active'));
        modal.querySelector('.modal-backdrop').addEventListener('click', () => modal.classList.remove('active'));

        // Search
        document.getElementById('search-toggle-btn').addEventListener('click', () => this.toggleSearch());
        document.getElementById('search-close-btn').addEventListener('click', () => this.toggleSearch());
        document.getElementById('search-input').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.performSearch();
        });

        // Reply/Edit indicator cancel
        document.getElementById('indicator-cancel').addEventListener('click', () => this.cancelIndicator());

        // Private chat modal
        document.getElementById('private-message-input').addEventListener('keypress', (e) => {
            if (e.key === 'Enter') this.sendPrivateMessage();
        });
        document.getElementById('private-send-btn').addEventListener('click', () => this.sendPrivateMessage());
        document.getElementById('private-chat-backdrop').addEventListener('click', () => this.closePrivateChat());
        document.getElementById('private-chat-close-btn').addEventListener('click', () => this.closePrivateChat());

        // User profile modal
        document.getElementById('user-profile-backdrop').addEventListener('click', () => this.closeUserProfile());
        document.getElementById('user-profile-close-btn').addEventListener('click', () => this.closeUserProfile());

        // Delegated: mention highlights and reply preview clicks
        const messagesContainer = document.getElementById('messages-container');
        messagesContainer.addEventListener('click', (e) => {
            const mention = e.target.closest('[data-mention-user]');
            if (mention) {
                this.openUserProfileByName(mention.dataset.mentionUser);
                return;
            }
            const avatar = e.target.closest('.message-avatar[data-username]');
            if (avatar) {
                this.openUserProfileByName(avatar.dataset.username);
                return;
            }
            const replyPreview = e.target.closest('.reply-preview');
            if (replyPreview) {
                const msgId = parseInt(replyPreview.dataset.replyId, 10);
                if (!isNaN(msgId)) this.scrollToMessage(msgId);
            }
        });

        // Scroll to load history
        messagesContainer.addEventListener('scroll', () => {
            if (messagesContainer.scrollTop < 50 && this.hasMoreHistory && !this.loadingHistory && this.oldestMessageId) {
                this.loadMoreHistory();
            }
        });
    }

    // ============ WebSocket Connection ============

    connect() {
        this.intentionalDisconnect = false;
        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        let wsUrl = `${protocol}//${window.location.host}/chat/${this.roomId}?token=${encodeURIComponent(this.authToken || '')}`;
        if (this.currentRoomPassword) {
            wsUrl += `&roomPassword=${encodeURIComponent(this.currentRoomPassword)}`;
        }
        this.ws = new WebSocket(wsUrl);
        this.ws.onopen = () => this.onConnected();
        this.ws.onmessage = (event) => this.handleMessage(JSON.parse(event.data));
        this.ws.onclose = () => this.onDisconnected();
        this.ws.onerror = (error) => {
            console.error('WebSocket error:', error);
            this.showToast('Connection error', 'error');
        };
    }

    onConnected() {
        this.reconnectAttempts = 0;
        this.showChatScreen();
        document.getElementById('current-room-name').textContent = this.roomName;
        document.getElementById('current-username').textContent = (this.currentUser && this.currentUser.username) || 'USER';
        document.getElementById('message-input').focus();
        this.showToast('Connected to ' + this.roomName, 'success');
    }

    onDisconnected() {
        if (this.intentionalDisconnect) return;
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
        document.getElementById('messages-container').innerHTML = '';
        document.getElementById('users-list').innerHTML = '';
        this.users = [];
        this.oldestMessageId = null;
        this.hasMoreHistory = true;
        this.currentRoomPassword = '';
        this.updateUnreadDmBadge(0);
        this.showLobbyScreen();
        this.showToast('Left room', 'success');
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
            case 'unread_counts':
                this.updateUnreadDmBadge(data.unreadDms || 0);
                break;
        }
    }
}
