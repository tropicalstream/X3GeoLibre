// Content script (isolated world). Relays page-context WebRTC/WebSocket lifecycle
// events to the app via native messaging so they land in logcat under TapGPT-WRTC.
(function () {
  // Report via document.title -> GeckoView ContentDelegate.onTitleChange (a reliable
  // channel that doesn't depend on extension native messaging).
  function toApp(text) {
    try { document.title = "WRTC|" + String(text); } catch (e) {}
  }
  window.addEventListener("message", function (e) {
    if (e.source === window && e.data && e.data.__wrtcmon) toApp(e.data.text);
  });
  toApp("content-script loaded " + location.href);

  // The page's RTCPeerConnection isn't visible from the isolated content-script
  // world, so inject a hook into the real page context.
  var pageCode = "(" + (function () {
    function post(t) { try { window.postMessage({ __wrtcmon: true, text: t }, "*"); } catch (e) {} }
    post("monitor installed " + location.href);
    try {
      var OrigPC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
      if (OrigPC) {
        var W = function () {
          var pc = Reflect.construct(OrigPC, arguments);
          post("PC created");
          ["iceconnectionstatechange", "connectionstatechange", "icegatheringstatechange"].forEach(function (evt) {
            pc.addEventListener(evt, function () {
              post(evt + " ice=" + pc.iceConnectionState + " conn=" + pc.connectionState + " gather=" + pc.iceGatheringState);
            });
          });
          var oc = pc.close.bind(pc);
          pc.close = function () { post("PC.close() called"); return oc(); };
          return pc;
        };
        W.prototype = OrigPC.prototype;
        try { Object.getOwnPropertyNames(OrigPC).forEach(function (k) { try { W[k] = OrigPC[k]; } catch (e) {} }); } catch (e) {}
        window.RTCPeerConnection = W;
      } else { post("no RTCPeerConnection"); }

      var OrigWS = window.WebSocket;
      if (OrigWS) {
        var WSW = function (u, p) {
          var ws = (p === undefined) ? new OrigWS(u) : new OrigWS(u, p);
          var us = String(u);
          if (us.indexOf("chatgpt") >= 0 || us.indexOf("openai") >= 0 || us.indexOf("realtime") >= 0) post("WS open " + us);
          ws.addEventListener("close", function (ev) { post("WS close " + us + " code=" + ev.code + " reason=" + ev.reason + " clean=" + ev.wasClean); });
          ws.addEventListener("error", function () { post("WS error " + us); });
          return ws;
        };
        WSW.prototype = OrigWS.prototype;
        try { ["CONNECTING", "OPEN", "CLOSING", "CLOSED"].forEach(function (k) { WSW[k] = OrigWS[k]; }); } catch (e) {}
        window.WebSocket = WSW;
      }
    } catch (e) { post("hook err " + (e && e.message)); }
  }).toString() + ")();";

  var s = document.createElement("script");
  s.textContent = pageCode;
  (document.head || document.documentElement).appendChild(s);
  s.remove();
})();
