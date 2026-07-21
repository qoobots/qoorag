/* =====================================================================
   common.js — 统一布局注入
   将每个内部页面的 <div class="container"> 包裹进「侧边栏 + 顶栏 + 内容区」
   结构，并高亮当前导航项、填充页面标题与用户信息。
   仅内部页面引入；login.html 为独立登录页，不引入。
   ===================================================================== */
(function () {
    const ICON = {
        home:    '<path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/>',
        settings:'<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>',
        database:'<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/><path d="M3 12c0 1.66 4 3 9 3s9-1.34 9-3"/>',
        document:'<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>',
        chat:    '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
        audit:   '<path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>',
        logout:  '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5"/><path d="M21 12H9"/>'
    };
    function svg(name) {
        return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
               'stroke-linecap="round" stroke-linejoin="round">' + ICON[name] + '</svg>';
    }

    const NAV = [
        { href: '/index.html',       key: 'index',  label: '概览',     icon: 'home' },
        { href: '/system-admin.html', key: 'system', label: '系统管理', icon: 'settings' },
        { href: '/rag-admin.html',   key: 'kb',     label: '知识库管理', icon: 'database' },
        { href: '/documents.html',   key: 'docs',   label: '文档管理', icon: 'document' },
        { href: '/playground.html',  key: 'chat',   label: '问答测试', icon: 'chat' },
        { href: '/audit.html',       key: 'audit',  label: '审计日志', icon: 'audit' }
    ];
    const TITLES = {
        'index.html': '概览',
        'system-admin.html': '系统管理',
        'rag-admin.html': '知识库管理',
        'documents.html': '文档管理',
        'playground.html': '问答测试',
        'audit.html': '审计日志'
    };

    function render() {
        const container = document.querySelector('.container');
        if (!container) return;

        const path = location.pathname;
        const current = NAV.find(n => path.endsWith(n.href)) || NAV[0];

        // 侧边栏
        const sidebar = document.createElement('aside');
        sidebar.className = 'sidebar';
        sidebar.innerHTML =
            '<div class="sidebar-brand">' +
                '<span class="logo">Q</span>' +
                '<div><div class="brand-name">qoorag</div><div class="brand-sub">知识库平台</div></div>' +
            '</div>' +
            '<nav class="sidebar-nav">' +
                NAV.map(n =>
                    '<a href="' + n.href + '"' + (n.key === current.key ? ' class="active"' : '') + '>' +
                        svg(n.icon) + '<span>' + n.label + '</span></a>'
                ).join('') +
            '</nav>' +
            '<div class="sidebar-footer">' +
                '<button class="ghost" onclick="if(window.logout)logout();else{localStorage.removeItem(\'qoorag_token\');location.href=\'/login.html\';}">' +
                    svg('logout') + '<span>退出登录</span></button>' +
            '</div>';

        // 顶栏
        const topbar = document.createElement('header');
        topbar.className = 'topbar';
        topbar.innerHTML =
            '<div class="topbar-title">' + (TITLES[current.key] || '') + '</div>' +
            '<div class="topbar-user" id="topUser"></div>';

        // 主区域
        const main = document.createElement('div');
        main.className = 'main';
        container.className = 'content';
        main.appendChild(topbar);
        main.appendChild(container);

        const layout = document.createElement('div');
        layout.className = 'layout';
        layout.appendChild(sidebar);
        layout.appendChild(main);

        document.body.appendChild(layout);

        loadUser();
    }

    async function loadUser() {
        const el = document.getElementById('topUser');
        if (!el) return;
        try {
            const r = await api('GET', '/api/auth/me');
            if (r.code === 0) {
                const d = r.data;
                const name = (d.roles && d.roles.length) ? d.roles[0] : '用户';
                el.innerHTML = '<span>' + esc(name) + '</span>' +
                    '<span class="avatar">' + esc((name[0] || 'U')) + '</span>';
            }
        } catch (e) { /* 忽略，顶栏仅展示 */ }
    }

    function esc(s) {
        return (s == null ? '' : String(s))
            .replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', render);
    } else {
        render();
    }
})();
