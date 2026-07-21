// 统一的 API 封装：自动附带 Bearer 令牌（登录后存入 localStorage）

/** HTML 转义，防止 XSS 与渲染错乱（全局可用，各页面无需重复定义） */
function esc(s) {
    return (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;')
        .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
const TOKEN_KEY = 'qoorag_token';

function getToken() { return localStorage.getItem(TOKEN_KEY) || ''; }

/**
 * 通用请求封装。
 * @param tokenOverride 可选，覆盖默认令牌（如用 API Key 调用 /api/v1）
 * @param noAuthRedirect 可选，为 true 时 401 不跳登录页（用于 /api/v1 用 API Key 鉴权的调用，
 *        401 代表 API Key 无效，应在页面内展示错误而非清除会话）
 * body 为 FormData 时自动以 multipart 发送，不设置 JSON Content-Type
 */
async function api(method, path, body, tokenOverride, noAuthRedirect) {
    const headers = {};
    const token = tokenOverride || getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    if (body && !(body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(path, {
        method,
        headers,
        body: body ? (body instanceof FormData ? body : JSON.stringify(body)) : undefined
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && !noAuthRedirect) {
        localStorage.removeItem(TOKEN_KEY);
        location.href = '/login.html';
        throw new Error('未登录');
    }
    return data;
}

/** 上传文档（multipart，支持多文件），使用会话令牌。files 可为单个 File 或 File 数组 */
async function uploadDocument(kbId, files) {
    const form = new FormData();
    const arr = Array.isArray(files) ? files : [files];
    arr.forEach(f => form.append('files', f));
    return api('POST', '/api/kb/' + kbId + '/documents', form);
}

/** 审计日志导出为 CSV 并触发浏览器下载 */
async function downloadAuditExport(query) {
    const token = getToken();
    const qs = new URLSearchParams();
    if (query.action) qs.set('action', query.action);
    if (query.objectType) qs.set('objectType', query.objectType);
    if (query.start) qs.set('start', query.start);
    if (query.end) qs.set('end', query.end);
    const res = await fetch('/api/admin/audit/export?' + qs.toString(), {
        headers: token ? { 'Authorization': 'Bearer ' + token } : {}
    });
    if (res.status === 401) {
        localStorage.removeItem(TOKEN_KEY);
        location.href = '/login.html';
        throw new Error('未登录');
    }
    if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || ('导出失败: ' + res.status));
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'audit_log.csv';
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

function requireToken() {
    if (!getToken()) { location.href = '/login.html'; }
}

function logout() {
    api('POST', '/api/auth/logout').finally(() => {
        localStorage.removeItem(TOKEN_KEY);
        location.href = '/login.html';
    });
}
