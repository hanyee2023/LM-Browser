// assets/sniffer.js —— 视频嗅探注入脚本
// 钩住 <video>、fetch、XMLHttpRequest，捕获媒体资源 URL，通过 JSBridge 上报给 Kotlin 层。
(function () {
  if (window.__snifferInjected) return;
  window.__snifferInjected = true;

  const MEDIA_EXT = /\.(m3u8|mp4|flv|m4s|ts|webm|mov|mkv)(\?|$)/i;
  const reported = new Set();

  function report(url, source) {
    if (!url || reported.has(url)) return;
    // 只关心明显是媒体的链接
    if (!MEDIA_EXT.test(url) && !/video|m3u8|play/i.test(url)) return;
    reported.add(url);
    try {
      // window.SnifferBridge 由 Kotlin 通过 addJavascriptInterface 提供
      if (window.SnifferBridge) {
        window.SnifferBridge.onVideo(url, source || "unknown");
      }
    } catch (e) {}
  }

  // 1) 监听 <video> 元素的 src / srcObject
  document.addEventListener("play", function (e) {
    const v = e.target;
    if (v && v.currentSrc) report(v.currentSrc, "video");
  }, true);

  const obs = new MutationObserver(function (muts) {
    muts.forEach(function (m) {
      m.addedNodes.forEach(function (n) {
        if (n.tagName === "VIDEO" && n.src) report(n.src, "video-tag");
      });
    });
  });
  obs.observe(document.documentElement, { childList: true, subtree: true });

  // 2) 钩住 fetch
  const _fetch = window.fetch;
  window.fetch = function (input, init) {
    const url = typeof input === "string" ? input : (input && input.url);
    if (url) report(url, "fetch");
    return _fetch.apply(this, arguments);
  };

  // 3) 钩住 XMLHttpRequest
  const _open = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    if (url) report(url, "xhr");
    return _open.apply(this, arguments);
  };

  console.log("[sniffer] injected");
})();
