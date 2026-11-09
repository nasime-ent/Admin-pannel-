(function () {
  var BUTTON_ID = 'kichsNativeFingerprintEnrollButton';

  function findSecuritySettingsHeading() {
    var headings = document.querySelectorAll('h1,h2,h3,h4,[role="heading"],.page-title,.section-title');
    for (var i = 0; i < headings.length; i++) {
      var el = headings[i];
      if (!el || !el.getClientRects().length) continue;
      var label = (el.innerText || el.textContent || '').trim().replace(/\\s+/g, ' ');
      if (/^security\\s+settings$/i.test(label) || /^security$/i.test(label)) return el;
    }
    return null;
  }

  function updateButton() {
    var oldButton = document.getElementById(BUTTON_ID);
    var heading = findSecuritySettingsHeading();

    // The enrollment control must exist only on the Security Settings screen.
    if (!heading) {
      if (oldButton) oldButton.remove();
      return;
    }
    if (oldButton) return;

    var button = document.createElement('button');
    button.id = BUTTON_ID;
    button.type = 'button';
    button.textContent = '🔐  SET UP / USE FINGERPRINT';
    button.setAttribute('aria-label', 'Set up or use fingerprint security');
    button.style.cssText = 'display:block;width:100%;min-height:48px;margin:14px 0;padding:12px 16px;border:0;border-radius:10px;background:#1264c5;color:#fff;font-size:15px;font-weight:700;box-shadow:0 3px 10px rgba(0,0,0,.18);cursor:pointer;';
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopPropagation();
      if (window.KICHSBiometric && typeof window.KICHSBiometric.setupFingerprint === 'function') {
        window.KICHSBiometric.setupFingerprint();
      } else {
        alert('Fingerprint security is available in the installed KICHS Android app. Please reopen the app and try again.');
      }
    });

    // Place it within the security settings content, not as a floating dashboard button.
    var host = heading.parentElement || heading;
    host.appendChild(button);
  }

  var queued = false;
  function scheduleUpdate() {
    if (queued) return;
    queued = true;
    setTimeout(function () {
      queued = false;
      updateButton();
    }, 150);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleUpdate);
  } else {
    scheduleUpdate();
  }

  // Re-check when the app navigates between dashboard and settings views.
  var observer = new MutationObserver(scheduleUpdate);
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
  document.addEventListener('click', function () { setTimeout(scheduleUpdate, 250); }, true);
})();