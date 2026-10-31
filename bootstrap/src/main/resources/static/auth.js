// Token 只保存在当前标签页的 sessionStorage；不通过 URL、Cookie 或页面文本传播。
window.RagentAuth = (() => {
  const key = 'ragent-learning-token';
  function clear() { sessionStorage.removeItem(key); }
  async function request(url, options = {}) {
    const target = new URL(url, location.href);
    if (target.origin !== location.origin) throw new Error('只允许请求当前服务');
    const headers = new Headers(options.headers);
    const token = sessionStorage.getItem(key);
    if (token) headers.set('Authorization', 'Bearer ' + token);
    const response = await fetch(target, {...options, headers, credentials: 'omit', cache: 'no-store'});
    if (response.status === 401) {
      clear();
      window.dispatchEvent(new Event('ragent-auth-expired'));
    }
    return response;
  }
  return {fetch: request, set: token => sessionStorage.setItem(key, token), clear};
})();
