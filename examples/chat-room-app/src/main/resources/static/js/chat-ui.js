// ============================================
// CHAT UI — Utilities, Markdown, Mentions, Search
// ============================================

Object.assign(ChatClient.prototype, {

    // ============ Markdown ============

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
    },

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
    },

    highlightMentions(html) {
        return html.replace(/@(\w+)/g, (match, username) => {
            const isSelf = username === this.username;
            return `<span class="mention-highlight${isSelf ? ' mention-self' : ''}" data-mention-user="${this.escapeAttr(username)}">${this.escapeHtml(match)}</span>`;
        });
    },

    // ============ Toast Notifications ============

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
    },

    // ============ Mentions Autocomplete ============

    handleMentionInput(e) {
        const input = e.target;
        const value = input.value;
        const cursorPos = input.selectionStart;
        const textBefore = value.substring(0, cursorPos);
        const atMatch = textBefore.match(/@(\w*)$/);
        if (atMatch) {
            const query = atMatch[1].toLowerCase();
            const matches = this.users.filter(u =>
                u.username.toLowerCase().startsWith(query)
            ).slice(0, 5);
            if (matches.length > 0) {
                this.showMentionDropdown(matches, atMatch[0].length);
                return;
            }
        }
        this.hideMentionDropdown();
    },

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
    },

    showMentionDropdown(users, matchLen) {
        const dropdown = document.getElementById('mention-dropdown');
        dropdown.innerHTML = '';
        users.forEach((u, i) => {
            const item = document.createElement('div');
            item.className = `mention-item${i === 0 ? ' selected' : ''}`;
            item.setAttribute('data-username', u.username);
            item.innerHTML = `
                <span class="mention-item-name">${this.escapeHtml(u.username)}</span>
                <span class="mention-item-username">@${this.escapeHtml(u.username)}</span>
            `;
            item.addEventListener('click', () => this.insertMention(u.username));
            dropdown.appendChild(item);
        });
        dropdown.style.display = 'block';
        this.mentionDropdownVisible = true;
    },

    hideMentionDropdown() {
        document.getElementById('mention-dropdown').style.display = 'none';
        this.mentionDropdownVisible = false;
    },

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
    },

    insertMentionInChat(username) {
        this.closeUserProfile();
        const input = document.getElementById('message-input');
        input.value += `@${username} `;
        input.focus();
    },

    handleMention(data) {
        this.showToast(`${data.mentionedBy} mentioned you`, 'success');
        if (Notification.permission === 'granted') {
            new Notification('TERMINAL.CHAT', { body: `${data.mentionedBy} mentioned you` });
        } else if (Notification.permission !== 'denied') {
            Notification.requestPermission();
        }
    },

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
    },

    performSearch() {
        const query = document.getElementById('search-input').value.trim();
        if (!query || !this.ws) return;
        this.ws.send(JSON.stringify({ type: 'search', query }));
    },

    renderSearchResults(messages, query) {
        const container = document.getElementById('search-results');
        container.style.display = 'block';
        if (messages.length === 0) {
            container.innerHTML = '<div class="search-no-results">No messages found</div>';
            return;
        }
        container.innerHTML = '';
        messages.forEach(msg => {
            const name = msg.username || 'Unknown';
            const content = msg.content || '';
            const time = this.formatTime(msg.timestamp);
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
    },

    // ============ Image Modal ============

    openImageModal(imageUrl) {
        const modal = document.getElementById('image-modal');
        const img = document.getElementById('modal-image');
        img.src = imageUrl;
        modal.classList.add('active');
    },

    // ============ Utilities ============

    scrollToBottom() {
        const container = document.getElementById('messages-container');
        container.scrollTop = container.scrollHeight;
    },

    getAvatar(name) {
        return (name || '?').charAt(0).toUpperCase();
    },

    formatTime(timestamp) {
        const date = new Date(timestamp);
        const hours = date.getHours().toString().padStart(2, '0');
        const minutes = date.getMinutes().toString().padStart(2, '0');
        return `${hours}:${minutes}`;
    },

    formatFileSize(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
    },

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = String(text);
        return div.innerHTML;
    },

    escapeAttr(text) {
        return String(text).replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"');
    },

    escapeRegex(str) {
        return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    },

    // ============ Unread DM Badge ============

    updateUnreadDmBadge(count) {
        const btn = document.getElementById('dm-badge-btn');
        const badge = document.getElementById('dm-unread-count');
        if (count > 0) {
            badge.textContent = count > 99 ? '99+' : count;
            btn.style.display = 'flex';
        } else {
            btn.style.display = 'none';
        }
    }
});
