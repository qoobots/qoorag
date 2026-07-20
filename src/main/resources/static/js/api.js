// 简单的 API 封装：自动附带 Bearer 令牌（登录后存入 localStorage）
const TOKEN_KEY = 'qoorag_token';

function getToken() { return localStorage.getItem(TOKEN_KEY) || ''; }

async function api(method, path, body) {
    const headers = { 'Content-Type': 'application/json' };
    const token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(path, {
        method,
        headers,
        body: body ? JSON.stringify(body) : undefined
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        location.href = '/login.html';
        throw new Error('未登录');
    }
    return data;
}

function requireToken() {
    if (!getToken()) { location.href = '/login.html'; }
}
