(function () {
  function installKichsSettingsFingerprintButton() {
    if (!document.body || document.getElementById('kichsNativeFingerprintEnrollButton')) return;
    var button = document.createElement('button');
    button.id = 'kichsNativeFingerprintEnrollButton';
    button.type = 'button';
    button.textContent = '🔐  SET UP / USE FINGERPRINT';
    button.setAttribute('aria-label', 'Set up or use fingerprint security');
    button.style.cssText = [
      'position:fixed',
      'left:16px',
      'right:16px',
      'bottom:calc(18px + env(safe-area-inset-bottom))',
      'z-index:2147483647',
      'display:block',
      'width:calc(100% - 32px)',
      'min-height:54px',
      'padding:14px 18px',
      'border:0',
      'border-radius:12px',
      'background:#1264c5',
      'color:#fff',
      'font-size:16px',
      'font-weight:700',
      'box-shadow:0 4px 16px rgba(0,0,0,.28)',
      'cursor:pointer'
    ].join(';');
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      if (window.KICHSBiometric && typeof window.KICHSBiometric.setupFingerprint === 'function') {
        window.KICHSBiometric.setupFingerprint();
      } else {
        alert('Fingerprint security is available in the installed KICHS Android app. Please reopen the app and try again.');
      }
    });
    document.body.appendChild(button);
  }

  document.addEventListener('click', function (event) {
    var target = event.target && event.target.closest
      ? event.target.closest('button,a,[role="button"],[role="tab"]')
      : null;
    if (!target) return;
    var label = (target.innerText || target.textContent || target.getAttribute('aria-label') || '').trim();
    if (/settings/i.test(label)) {
      setTimeout(installKichsSettingsFingerprintButton, 250);
      setTimeout(installKichsSettingsFingerprintButton, 700);
      setTimeout(installKichsSettingsFingerprintButton, 1400);
    }
  }, true);
})();