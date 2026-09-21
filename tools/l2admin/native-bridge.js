/*
 * native-bridge.js - desktop app file bridge for l2admin.
 *
 * l2admin is a single self-contained index.html. In a browser it reads and
 * writes the server's files with the File System Access API (showDirectoryPicker,
 * createWritable, ...). That API is Chromium only and depends on a browser
 * permission grant that can silently lapse, which is what makes "open the game
 * folder" fail to detect the .ini files.
 *
 * When l2admin runs inside the desktop host (L2Admin-App.ps1, a WebView2 window),
 * the host injects this file BEFORE any page script runs. It replaces the browser
 * file API with directory and file handles that are backed by the host process,
 * which reads and writes with normal OS file access. No browser, no picker
 * permission, so the folder is always readable.
 *
 * The handle objects here implement the same shape the page already uses
 * (values(), getDirectoryHandle, getFileHandle, getFile, createWritable,
 * queryPermission, requestPermission), so index.html itself needs no rewrite.
 *
 * Wire protocol (page -> host as a JSON message, host -> page as a JSON reply):
 *   page: { rid, op, ...args }
 *   host: { rid, ok:true, result:{...} }  or  { rid, ok:false, error, name }
 * ops: pickDir, pickFile, list, readBytes, writeBytes, mkdir, stat
 */
(function (global) {
  'use strict';

  // ---- pure helpers (also exported for Node tests) -----------------------

  function joinPath(dir, name) {
    if (!dir) return name;
    return dir.replace(/[\\/]+$/, '') + '/' + name;
  }

  function baseName(path) {
    return String(path).replace(/[\\/]+$/, '').replace(/.*[\\/]/, '');
  }

  function b64ToBytes(b64) {
    var bin = atob(b64 || '');
    var len = bin.length;
    var bytes = new Uint8Array(len);
    for (var i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i);
    return bytes;
  }

  function bytesToB64(bytes) {
    var CHUNK = 0x8000, out = '';
    for (var i = 0; i < bytes.length; i += CHUNK) {
      out += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + CHUNK, bytes.length)));
    }
    return btoa(out);
  }

  // Normalize the many shapes a FileSystemWritableFileStream.write() accepts
  // (string, Blob, ArrayBuffer, typed array, or a { type:'write', data } record)
  // into raw bytes for the host to write.
  async function toBytes(data) {
    if (data == null) return new Uint8Array(0);
    if (typeof data === 'string') return new TextEncoder().encode(data);
    if (typeof Blob !== 'undefined' && data instanceof Blob) return new Uint8Array(await data.arrayBuffer());
    if (data instanceof ArrayBuffer) return new Uint8Array(data);
    if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    if (typeof data === 'object' && 'data' in data) return toBytes(data.data);
    return new TextEncoder().encode(String(data));
  }

  // ---- browser install (only inside the WebView2 host) -------------------
  // Wires window.showDirectoryPicker / showOpenFilePicker and the handle objects
  // over the host bridge. Exported as installInto() so a test can drive it with a
  // fake window + in-memory host; auto-run at the bottom when inside WebView2.
  function installInto(global) {
  var webview = global.chrome.webview;
  var seq = 0;
  var pending = Object.create(null);
  var DOMEx = (typeof global.DOMException !== 'undefined') ? global.DOMException
    : function (m, n) { var e = new Error(m); e.name = n || 'Error'; return e; };
  var FileCtor = global.File;

  function call(op, args) {
    return new Promise(function (resolve, reject) {
      var rid = ++seq;
      pending[rid] = { resolve: resolve, reject: reject };
      var msg = { rid: rid, op: op };
      if (args) for (var k in args) msg[k] = args[k];
      webview.postMessage(msg);
    });
  }

  webview.addEventListener('message', function (e) {
    var d = e.data;
    if (typeof d === 'string') { try { d = JSON.parse(d); } catch (_) { return; } }
    if (!d || typeof d.rid === 'undefined') return;
    var p = pending[d.rid];
    if (!p) return;
    delete pending[d.rid];
    if (d.ok) p.resolve(d.result || {});
    else p.reject(new DOMEx(d.error || 'native bridge error', d.name || 'InvalidStateError'));
  });

  // ---- FileSystemFileHandle-compatible object ----------------------------

  function FileHandle(path, name) {
    this.kind = 'file';
    this.name = name;
    this._path = path;
  }
  FileHandle.prototype.isSameEntry = function (o) { return !!o && o._path === this._path; };
  FileHandle.prototype.queryPermission = function () { return Promise.resolve('granted'); };
  FileHandle.prototype.requestPermission = function () { return Promise.resolve('granted'); };
  FileHandle.prototype.getFile = async function () {
    var res = await call('readBytes', { path: this._path });
    var bytes = b64ToBytes(res.base64 || '');
    return new FileCtor([bytes], this.name, { lastModified: res.mtime || Date.now() });
  };
  FileHandle.prototype.createWritable = async function () {
    var path = this._path;
    var chunks = [];
    return {
      write: async function (data) { chunks.push(await toBytes(data)); },
      truncate: async function () { chunks = []; },
      seek: async function () { /* single-shot writers only; no partial seeks in l2admin */ },
      close: async function () {
        var total = 0, i;
        for (i = 0; i < chunks.length; i++) total += chunks[i].length;
        var buf = new Uint8Array(total), off = 0;
        for (i = 0; i < chunks.length; i++) { buf.set(chunks[i], off); off += chunks[i].length; }
        await call('writeBytes', { path: path, base64: bytesToB64(buf) });
      }
    };
  };

  // ---- FileSystemDirectoryHandle-compatible object -----------------------

  function DirHandle(path, name) {
    this.kind = 'directory';
    this.name = name;
    this._path = path;
  }
  DirHandle.prototype.isSameEntry = function (o) { return !!o && o._path === this._path; };
  DirHandle.prototype.queryPermission = function () { return Promise.resolve('granted'); };
  DirHandle.prototype.requestPermission = function () { return Promise.resolve('granted'); };
  DirHandle.prototype.getDirectoryHandle = async function (name, opts) {
    var childPath = joinPath(this._path, name);
    if (opts && opts.create) {
      await call('mkdir', { path: childPath });
    } else {
      var st = await call('stat', { path: childPath });
      if (!st.exists || st.kind !== 'directory') throw new DOMEx('directory not found: ' + name, 'NotFoundError');
    }
    return new DirHandle(childPath, name);
  };
  DirHandle.prototype.getFileHandle = async function (name, opts) {
    var childPath = joinPath(this._path, name);
    if (!(opts && opts.create)) {
      var st = await call('stat', { path: childPath });
      if (!st.exists || st.kind !== 'file') throw new DOMEx('file not found: ' + name, 'NotFoundError');
    }
    // With { create:true } the file is materialised on first write (createWritable/close).
    return new FileHandle(childPath, name);
  };
  DirHandle.prototype.values = function () {
    var self = this;
    return makeAsyncIter(function (e, childPath) {
      return e.kind === 'directory' ? new DirHandle(childPath, e.name) : new FileHandle(childPath, e.name);
    }, self);
  };
  DirHandle.prototype.keys = function () {
    var self = this;
    return makeAsyncIter(function (e) { return e.name; }, self);
  };
  DirHandle.prototype.entries = function () {
    var self = this;
    return makeAsyncIter(function (e, childPath) {
      var h = e.kind === 'directory' ? new DirHandle(childPath, e.name) : new FileHandle(childPath, e.name);
      return [e.name, h];
    }, self);
  };

  function makeAsyncIter(map, dir) {
    var iter = {};
    iter[Symbol.asyncIterator] = function () {
      var entries = null, i = 0;
      return {
        next: async function () {
          if (entries === null) {
            var res = await call('list', { path: dir._path });
            entries = res.entries || [];
          }
          if (i >= entries.length) return { done: true, value: undefined };
          var e = entries[i++];
          return { done: false, value: map(e, joinPath(dir._path, e.name)) };
        }
      };
    };
    return iter;
  }

  // ---- window pickers replacing the browser's ----------------------------

  global.showDirectoryPicker = async function () {
    var res = await call('pickDir', {});
    if (!res || res.cancelled) throw new DOMEx('The user aborted a request.', 'AbortError');
    return new DirHandle(res.path, res.name || baseName(res.path));
  };
  global.showOpenFilePicker = async function () {
    var res = await call('pickFile', {});
    if (!res || res.cancelled) throw new DOMEx('The user aborted a request.', 'AbortError');
    return [new FileHandle(res.path, res.name || baseName(res.path))];
  };

  // ---- handoff to index.html ---------------------------------------------
  // The host injects a second one-line script after this file that sets
  // __l2native.gameDir to the server's game folder. index.html, at the end of
  // its own script, calls openGameDir(loadFromHandle) to open that folder with
  // zero clicks and no picker.
  global.__l2native = {
    version: 1,
    gameDir: null,
    makeDirHandle: function (path, name) { return new DirHandle(path, name || baseName(path)); },
    openGameDir: async function (loader) {
      var dir = global.__l2native.gameDir;
      if (!dir || typeof loader !== 'function') return false;
      try { await loader(new DirHandle(dir, baseName(dir))); return true; }
      catch (err) { console.error('l2admin native auto-open failed', err); return false; }
    }
  };
  } // end installInto

  // Node export: the pure helpers plus installInto, so the bridge is unit testable
  // without a browser. Harmless in the browser (module is undefined there).
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      joinPath: joinPath, baseName: baseName,
      b64ToBytes: b64ToBytes, bytesToB64: bytesToB64, toBytes: toBytes,
      installInto: installInto
    };
  }

  // Auto-run only inside the WebView2 host. In a normal browser or Node there is
  // no chrome.webview, so the page's own behaviour is left untouched.
  var _g = (typeof window !== 'undefined') ? window : null;
  if (_g && _g.chrome && _g.chrome.webview) installInto(_g);
})(typeof window !== 'undefined' ? window : this);
