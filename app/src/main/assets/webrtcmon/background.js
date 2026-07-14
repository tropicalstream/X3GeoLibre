// Relays content-script messages to the app via native messaging (content scripts
// can't call sendNativeMessage themselves).
browser.runtime.onMessage.addListener(function (msg) {
  try { browser.runtime.sendNativeMessage("wrtcmon", String(msg)); } catch (e) {}
});
