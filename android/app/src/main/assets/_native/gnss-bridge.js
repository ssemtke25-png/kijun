/* 기준점 조사 APK 전용 브리지
 * - 블루투스 GNSS(NMEA)를 받아 navigator.geolocation 자리에 넣어 줌 (기존 웹앱 코드 수정 불필요)
 * - <a download> 저장을 Download/kijun 폴더 저장으로 바꿔 줌
 * 웹 브라우저에서는 AndroidGNSS가 없으므로 아무 동작도 하지 않음
 */
(function () {
  if (window.__gnssShimInstalled) return;
  var A = window.AndroidGNSS;
  if (!A) return;
  window.__gnssShimInstalled = true;

  // ---------------------------------------------------------------- 상태
  var st = {
    state: 'idle', device: '', fix: 0, sats: 0, hdop: null, acc: null,
    lat: null, lon: null, alt: null, geoidSep: null, age: null,
    gst: null, gstAt: 0, lastRx: 0
  };
  var FIX = { 0: '무효', 1: '단독', 2: 'DGPS', 4: 'RTK Fix', 5: 'RTK Float', 6: '추정', 9: 'SBAS' };

  function fire(name, detail) {
    try { window.dispatchEvent(new CustomEvent(name, { detail: detail })); } catch (e) {}
  }

  // GNSS 값이 유효할 때만 폰 GPS 대신 사용
  function useGnss() {
    return st.state === 'connected' && st.fix > 0 && st.lat != null && (Date.now() - st.lastRx) < 3000;
  }

  window.GNSS = {
    get status() { return st; },
    get active() { return useGnss(); },
    fixName: function (q) { return FIX[q] || ('Q' + q); },
    connect: function () { A.pick(); },
    disconnect: function () { A.disconnect(); }
  };

  // ---------------------------------------------------------------- NMEA
  function checksumOk(s) {
    var i = s.indexOf('*');
    if (i < 0) return true;
    var c = 0;
    for (var k = 1; k < i; k++) c ^= s.charCodeAt(k);
    return parseInt(s.substr(i + 1, 2), 16) === c;
  }

  function dm(v, h) {
    if (!v) return null;
    var d = v.indexOf('.');
    if (d < 0) d = v.length;
    var deg = parseFloat(v.slice(0, d - 2));
    var min = parseFloat(v.slice(d - 2));
    if (isNaN(deg) || isNaN(min)) return null;
    var x = deg + min / 60;
    return (h === 'S' || h === 'W') ? -x : x;
  }

  function num(v) { if (v === undefined || v === '') return null; var n = parseFloat(v); return isNaN(n) ? null : n; }

  function accuracy() {
    if (st.gst && Date.now() - st.gstAt < 5000) {
      return Math.sqrt(st.gst.lat * st.gst.lat + st.gst.lon * st.gst.lon);
    }
    var h = st.hdop || 1;
    switch (st.fix) {
      case 4: return 0.02;
      case 5: return 0.3;
      case 2: case 9: return Math.max(0.5, h * 0.8);
      default: return Math.max(2, h * 3);
    }
  }

  function makePos() {
    return {
      coords: {
        latitude: st.lat, longitude: st.lon, altitude: st.alt, accuracy: st.acc,
        altitudeAccuracy: (st.gst && st.gst.alt != null) ? st.gst.alt : null,
        heading: null, speed: null,
        // 추가 정보 (웹앱에서 필요하면 사용)
        fixQuality: st.fix, fixName: FIX[st.fix] || '', satellites: st.sats, hdop: st.hdop,
        geoidSeparation: st.geoidSep, correctionAge: st.age, device: st.device, source: 'GNSS'
      },
      timestamp: Date.now()
    };
  }

  function onLine(line) {
    if (!checksumOk(line)) return;
    var f = line.split('*')[0].split(',');
    var t = f[0].slice(3);
    if (t === 'GGA') {
      st.fix = parseInt(f[6] || '0', 10) || 0;
      st.sats = parseInt(f[7] || '0', 10) || 0;
      st.hdop = num(f[8]);
      st.lastRx = Date.now();
      if (st.fix > 0) {
        st.lat = dm(f[2], f[3]);
        st.lon = dm(f[4], f[5]);
        st.alt = num(f[9]);
        st.geoidSep = num(f[11]);
        st.age = num(f[13]);
        st.acc = accuracy();
        emitPos();
      }
      render();
      fire('gnss', st);
    } else if (t === 'GST') {
      var la = num(f[6]), lo = num(f[7]), al = num(f[8]);
      if (la != null && lo != null) { st.gst = { lat: la, lon: lo, alt: al }; st.gstAt = Date.now(); }
    }
    fire('gnssnmea', line);
  }

  // ---------------------------------------------------------------- geolocation 대체
  var geo = navigator.geolocation;
  var watchers = {};
  var nextId = 900001;
  if (geo) {
    var oWatch = geo.watchPosition.bind(geo);
    var oGet = geo.getCurrentPosition.bind(geo);
    var oClear = geo.clearWatch.bind(geo);

    geo.watchPosition = function (succ, err, opt) {
      var id = nextId++;
      var w = { succ: succ, err: err };
      w.nativeId = oWatch(
        function (p) { if (!useGnss()) succ(p); },
        function (e) { if (!useGnss() && err) err(e); },
        opt
      );
      watchers[id] = w;
      if (useGnss()) setTimeout(function () { try { succ(makePos()); } catch (e) {} }, 0);
      return id;
    };
    geo.clearWatch = function (id) {
      var w = watchers[id];
      if (w) { oClear(w.nativeId); delete watchers[id]; } else oClear(id);
    };
    geo.getCurrentPosition = function (succ, err, opt) {
      if (useGnss()) { setTimeout(function () { try { succ(makePos()); } catch (e) {} }, 0); return; }
      return oGet(succ, err, opt);
    };
  }

  function emitPos() {
    if (!useGnss()) return;
    var p = makePos();
    for (var id in watchers) {
      try { watchers[id].succ(p); } catch (e) { console.error(e); }
    }
  }

  // ---------------------------------------------------------------- 상태 표시 버튼
  var el = null;
  function ensureEl() {
    if (el) return el;
    if (!document.body) return null;
    el = document.createElement('div');
    el.id = 'gnss-pill';
    el.style.cssText = 'position:fixed;top:158px;right:8px;z-index:99999;padding:7px 12px;border-radius:18px;' +
      'font:600 13px/1.3 sans-serif;color:#fff;background:#334155;box-shadow:0 2px 6px rgba(0,0,0,.35);' +
      'max-width:62vw;text-align:right;-webkit-user-select:none;user-select:none';
    el.addEventListener('click', function () { A.pick(); });
    document.body.appendChild(el);
    return el;
  }
  function fmtAcc(a) { return a < 1 ? (a * 100).toFixed(1) + 'cm' : a.toFixed(1) + 'm'; }
  function render() {
    if (!ensureEl()) return;
    var txt, bg;
    if (st.state === 'connected') {
      if (Date.now() - st.lastRx > 3000) {
        txt = '📡 ' + st.device + ' · 수신 없음'; bg = '#b45309';
      } else {
        txt = '📡 ' + (FIX[st.fix] || ('Q' + st.fix)) + ' · 위성 ' + st.sats + (st.fix > 0 && st.acc != null ? ' · ±' + fmtAcc(st.acc) : '');
        bg = st.fix === 4 ? '#15803d' : st.fix === 5 ? '#ca8a04' : '#b91c1c';
      }
    } else if (st.state === 'connecting' || st.state === 'retry') {
      txt = '📡 ' + st.device + ' 연결 중…'; bg = '#2563eb';
    } else {
      txt = '📡 GNSS 연결'; bg = '#334155';
    }
    el.textContent = txt;
    el.style.background = bg;
  }
  setInterval(render, 1000);
  document.addEventListener('DOMContentLoaded', render);

  // ---------------------------------------------------------------- 네이티브 → JS
  window.__gnssNative = function (type, data) {
    if (type === 'line') onLine(data);
    else if (type === 'status') {
      st.state = data.state;
      if (data.device) st.device = data.device;
      if (data.state !== 'connected') { st.fix = 0; }
      render();
      fire('gnssstatus', st);
    }
  };
  try { var s0 = JSON.parse(A.status()); window.__gnssNative('status', s0); } catch (e) {}

  // ---------------------------------------------------------------- 다운로드 → Download/kijun 저장
  var blobs = {};
  var oCreate = URL.createObjectURL, oRevoke = URL.revokeObjectURL;
  URL.createObjectURL = function (o) {
    var u = oCreate.call(URL, o);
    if (o instanceof Blob) blobs[u] = o;
    return u;
  };
  URL.revokeObjectURL = function (u) {
    setTimeout(function () { delete blobs[u]; oRevoke.call(URL, u); }, 60000);
  };

  function saveBlob(blob, name) {
    var fr = new FileReader();
    fr.onload = function () {
      var s = fr.result;
      A.saveFile(name, blob.type || '', s.slice(s.indexOf(',') + 1));
    };
    fr.onerror = function () { A.toast('저장 실패: 파일 읽기 오류'); };
    fr.readAsDataURL(blob);
  }
  function saveHref(href, name) {
    var b = blobs[href];
    if (b) { saveBlob(b, name); return; }
    fetch(href).then(function (r) { return r.blob(); })
      .then(function (bb) { saveBlob(bb, name); })
      .catch(function (e) { A.toast('저장 실패: ' + e); });
  }
  function isSaveLink(a) {
    return a && a.hasAttribute && a.hasAttribute('download') && /^(blob|data):/.test(a.href || '');
  }
  var oClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (isSaveLink(this)) { saveHref(this.href, this.getAttribute('download') || 'download'); return; }
    return oClick.call(this);
  };
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    if (isSaveLink(a)) {
      e.preventDefault();
      e.stopImmediatePropagation();
      saveHref(a.href, a.getAttribute('download') || 'download');
    }
  }, true);
})();
