// Applies the theme before the page paints (no white flash in dark mode).
(function () {
  var KEY = 'coffres.theme';
  var mode = 'auto';
  try { mode = localStorage.getItem(KEY) || 'auto'; } catch (e) { /* private mode */ }
  var mq = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
  function apply() {
    var t = mode === 'light' || mode === 'dark' ? mode : (mq && mq.matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', t);
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', t === 'dark' ? '#1C1C1E' : '#F2F2F7');
  }
  apply();
  if (mq && mq.addEventListener) mq.addEventListener('change', apply);
  window.coffresTheme = {
    get: function () { return mode; },
    set: function (v) { mode = v; try { localStorage.setItem(KEY, v); } catch (e) { /* ignore */ } apply(); }
  };
})();
