// Apply the saved theme/density before first paint to avoid a flash. An external file (not inline) so the
// Content-Security-Policy can forbid inline scripts entirely.
(function () {
  try {
    var t = localStorage.getItem('dallycontrol-theme');
    if (t !== 'light' && t !== 'dark') {
      t = matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
    }
    document.documentElement.setAttribute('data-theme', t);
    if (localStorage.getItem('dallycontrol-density') === 'compact') {
      document.documentElement.setAttribute('data-density', 'compact');
    }
  } catch (e) {}
})();
