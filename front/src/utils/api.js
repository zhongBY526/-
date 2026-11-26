import router from '@/router'

const BASE = ''  // 走 Vite 代理，相对路径即可

export async function api(path, options = {}) {
    const token = localStorage.getItem('token')
    const headers = { ...(options.headers || {}) }
    if (token) headers.Authorization = `Bearer ${token}`
    // 上传 FormData 时不要手动设 Content-Type，浏览器会自动带 boundary
    if (options.body && !(options.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json'
    }
    const res = await fetch(BASE + path, { ...options, headers })
    if (res.status === 401) {
        localStorage.removeItem('token')
        router.push('/login')
        throw new Error('未登录或登录已过期')
    }
    const json = await res.json()
    if (!res.ok || json.code !== 200) {
        throw new Error(json.message || `请求失败(${res.status})`)
    }
    return json.data
}