import { defineStore } from 'pinia'
import { api } from '@/utils/api'

export const useAuthStore = defineStore('auth', {
    state: () => ({
        token: localStorage.getItem('token') || '',
        user: JSON.parse(localStorage.getItem('user') || 'null'),
    }),
    getters: {
        isLoggedIn: (s) => !!s.token,
    },
    actions: {
        async login(username, password) {
            const data = await api('/api/auth/login', {
                method: 'POST',
                body: JSON.stringify({ username, password }),
            })
            this.token = data.token
            this.user = data.user
            localStorage.setItem('token', data.token)
            localStorage.setItem('user', JSON.stringify(data.user))
        },
        async logout() {
            try { await api('/api/auth/logout', { method: 'POST' }) } catch (e) { /* 忽略 */ }
            this.token = ''
            this.user = null
            localStorage.removeItem('token')
            localStorage.removeItem('user')
        },
    },
})