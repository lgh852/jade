/*
 * 공통 레이아웃 — 홈닷 허브 모양: 왼쪽 다크 사이드바(LNB 176px) + 상단 바(GNB 60px).
 * 페이지에서 <script src="/sidebar.js"></script> 한 줄로 붙인다.
 * 메뉴를 늘리려면 아래 MENUS 에 { label, href, icon(Material Symbols 이름) } 을 추가하면 된다.
 * 휴대폰·태블릿(900px 이하)에서는 사이드바가 서랍처럼 열린다.
 */
(function () {
  var MENUS = [
    { label: '근태 캘린더', href: '/calendar.html', icon: 'calendar_month' }
  ];

  if (!document.querySelector('link[data-ms]')) {
    var font = document.createElement('link');
    font.rel = 'stylesheet';
    font.setAttribute('data-ms', '');
    font.href = 'https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@20..24,400,0,0&display=block';
    document.head.appendChild(font);
  }

  var css =
    ':root{--lnb-w:176px;--gnb-h:60px}' +
    '.ms{font-family:"Material Symbols Outlined";font-weight:normal;font-style:normal;font-size:20px;line-height:1;letter-spacing:normal;text-transform:none;' +
    'white-space:nowrap;direction:ltr;-webkit-font-feature-settings:"liga";font-feature-settings:"liga";-webkit-font-smoothing:antialiased;display:inline-block;vertical-align:middle}' +
    /* LNB */
    '.lnb{position:fixed;top:0;left:0;bottom:0;width:var(--lnb-w);background:#2a3038;color:#fff;display:flex;flex-direction:column;z-index:25;transition:transform .2s ease-out}' +
    '.lnb-logo{height:var(--gnb-h);flex:none;display:flex;align-items:center;gap:8px;padding:0 20px;font-size:20px;font-weight:700;letter-spacing:-.01em;color:#fff;text-decoration:none}' +
    '.lnb-logo i{width:10px;height:10px;border-radius:50%;background:#5534e1;box-shadow:0 0 0 3px rgba(144,122,244,.35)}' +
    '.lnb-menu{display:flex;flex-direction:column;gap:4px;padding:12px}' +
    '.lnb-menu a{display:flex;align-items:center;gap:12px;height:34px;padding:6px 8px;border-radius:8px;color:#fff;text-decoration:none;font-size:14px;font-weight:500;transition:background .12s ease-out}' +
    '.lnb-menu a .ms{font-size:22px}' +
    '.lnb-menu a:hover{background:#555d6d}' +
    '.lnb-menu a.active{background:#5534e1;font-weight:700}' +
    '.lnb-foot{margin-top:auto;padding:12px 20px calc(16px + env(safe-area-inset-bottom));font-size:12px;line-height:19px;color:#868b94}' +
    /* GNB */
    '.gnb{position:fixed;top:0;left:var(--lnb-w);right:0;height:var(--gnb-h);background:#fff;border-bottom:1px solid #dcdee3;display:flex;align-items:center;gap:12px;padding:0 32px;z-index:20}' +
    '.gnb-menu{display:none;width:40px;height:40px;margin-left:-8px;border:none;border-radius:6px;background:transparent;color:#1a1c20;cursor:pointer;align-items:center;justify-content:center}' +
    '.gnb-menu:hover{background:#f3f4f5}' +
    '.gnb-menu .ms{font-size:24px}' +
    '.gnb-logo{display:none;font-size:18px;font-weight:700;color:#1a1c20;text-decoration:none}' +
    '.gnb-user{margin-left:auto;display:flex;align-items:center;gap:12px;min-width:0}' +
    '.gnb-me{display:flex;align-items:center;gap:6px;font-size:14px;font-weight:500;color:#2a3038;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}' +
    '.gnb-me .ms{color:#868b94}' +
    '.gnb-out{display:inline-flex;align-items:center;gap:6px;height:32px;padding:4px 12px;border:1px solid #dcdee3;border-radius:6px;background:#fff;color:#2a3038;font-family:inherit;font-size:14px;line-height:22px;font-weight:500;cursor:pointer;white-space:nowrap}' +
    '.gnb-out:hover{background:#f7f8f9}' +
    '.gnb-out .ms{font-size:16px}' +
    'body.has-lnb{padding-left:var(--lnb-w);padding-top:var(--gnb-h)}' +
    '.lnb-dim{display:none;position:fixed;inset:0;background:rgba(26,28,32,.7);z-index:24}' +
    '@media (max-width:900px){' +
    'body.has-lnb{padding-left:0;padding-top:calc(56px + env(safe-area-inset-top))}' +
    '.gnb{left:0;height:calc(56px + env(safe-area-inset-top));padding:env(safe-area-inset-top) 16px 0}' +
    '.gnb-menu,.gnb-logo{display:inline-flex}' +
    '.lnb{width:min(240px,80vw);transform:translateX(-100%);padding-top:env(safe-area-inset-top)}' +
    'body.lnb-open .lnb{transform:none}' +
    'body.lnb-open .lnb-dim{display:block}' +
    '}' +
    '@media (max-width:600px){' +
    '.gnb-me{display:none}' +
    '.gnb-out{padding:4px 10px;font-size:13px}' +
    '}';

  var style = document.createElement('style');
  style.textContent = css;
  document.head.appendChild(style);

  var path = location.pathname;
  function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

  // LNB
  var lnb = el('nav', 'lnb');
  lnb.setAttribute('aria-label', '메뉴');
  var logo = el('a', 'lnb-logo', '<i></i>Jade');
  logo.href = MENUS[0].href;
  lnb.appendChild(logo);
  var menu = el('div', 'lnb-menu');
  MENUS.forEach(function (m) {
    var a = el('a');
    a.href = m.href;
    if (path === m.href) { a.className = 'active'; a.setAttribute('aria-current', 'page'); }
    var icon = el('span', 'ms');
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = m.icon;
    var label = el('span');
    label.textContent = m.label;
    a.appendChild(icon);
    a.appendChild(label);
    menu.appendChild(a);
  });
  lnb.appendChild(menu);
  lnb.appendChild(el('div', 'lnb-foot', 'JadeHR 근태 도우미'));

  // GNB
  var gnb = el('header', 'gnb');
  var toggle = el('button', 'gnb-menu', '<span class="ms">menu</span>');
  toggle.type = 'button';
  toggle.setAttribute('aria-label', '메뉴 열기');
  var gLogo = el('a', 'gnb-logo', 'Jade');
  gLogo.href = MENUS[0].href;
  var user = el('div', 'gnb-user');
  var me = el('span', 'gnb-me');
  var logout = el('button', 'gnb-out', '<span class="ms" aria-hidden="true">logout</span>로그아웃');
  logout.type = 'button';
  logout.addEventListener('click', function () {
    try { localStorage.removeItem('jade.token'); localStorage.removeItem('jade.tokenAt'); } catch (e) {}
    fetch('/api/auth/logout', { method: 'POST' })
      .catch(function () {})
      .then(function () { location.replace('/login.html'); });
  });
  user.appendChild(me);
  user.appendChild(logout);
  gnb.appendChild(toggle);
  gnb.appendChild(gLogo);
  gnb.appendChild(user);

  fetch('/api/auth/me', { headers: { 'Accept': 'application/json' } })
    .then(function (res) { return res.ok ? res.json() : null; })
    .then(function (u) {
      if (!u) { user.style.display = 'none'; return; }
      var name = u.nickname && u.nickname !== u.id ? u.nickname + ' (' + u.id + ')' : u.id;
      me.innerHTML = '<span class="ms" aria-hidden="true">account_circle</span>';
      me.appendChild(document.createTextNode(name));
      me.title = name;
      backupToken();
    })
    .catch(function () { user.style.display = 'none'; });

  // 브라우저 저장소의 토큰 백업을 하루 한 번 새 토큰으로 바꾼다 (쿠키가 지워졌을 때 로그인 화면이 이걸로 복구)
  function backupToken() {
    var at = 0;
    try { at = parseInt(localStorage.getItem('jade.tokenAt'), 10) || 0; } catch (e) { return; }
    if (Date.now() - at < 24 * 60 * 60 * 1000) return;
    fetch('/api/auth/refresh', { method: 'POST', headers: { 'Accept': 'application/json' } })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (b) {
        if (!b || !b.token) return;
        try { localStorage.setItem('jade.token', b.token); localStorage.setItem('jade.tokenAt', String(Date.now())); } catch (e) {}
      })
      .catch(function () {});
  }

  var dim = el('div', 'lnb-dim');
  function close() { document.body.classList.remove('lnb-open'); }
  toggle.addEventListener('click', function () { document.body.classList.toggle('lnb-open'); });
  dim.addEventListener('click', close);
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') close(); });

  document.body.classList.add('has-lnb');
  document.body.insertBefore(dim, document.body.firstChild);
  document.body.insertBefore(gnb, document.body.firstChild);
  document.body.insertBefore(lnb, document.body.firstChild);
})();
