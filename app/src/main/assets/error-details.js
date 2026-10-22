/* Safe diagnostic fields only. The caller retains protocol success/failure checks. */
(function () {
  'use strict';

  const GENERIC = '未返回可安全复制的错误详情。';
  const HIDDEN_ROUTE = '[接口路径已隐藏]';
  const MAX_INPUT = 16000;
  const MAX_MESSAGE = 600;
  const SOURCES = new Set([
    'http', 'provider', 'task', 'native', 'native_unknown', 'transport',
    'timeout', 'client_timeout', 'tls', 'dns', 'connection', 'io',
    'validation', 'response', 'parse', 'native_timeout', 'native_dns',
    'native_tls', 'native_connect', 'native_io', 'native_validation', 'native_parse',
    'network_timeout', 'network_dns', 'network_tls', 'network_connect',
    'local_validation', 'local_parse', 'unknown'
  ]);

  function isScalar(value) {
    return typeof value === 'string' || typeof value === 'boolean'
      || (typeof value === 'number' && Number.isFinite(value));
  }

  function isRecord(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
    const prototype = Object.getPrototypeOf(value);
    return prototype === Object.prototype || prototype === null;
  }

  function readPath(value, path) {
    for (const key of path.split('.')) {
      if (!isRecord(value) || !Object.prototype.hasOwnProperty.call(value, key)) return undefined;
      value = value[key];
    }
    return value;
  }

  function firstScalar(value, paths) {
    for (const path of paths) {
      const candidate = readPath(value, path);
      if (isScalar(candidate) && String(candidate).trim()) return candidate;
    }
    return undefined;
  }

  // The bridge knows the actual configured key, which JavaScript must never read.
  // Missing/failed redaction is handled by returning fixed text, not the input.
  function redactConfiguredKey(value) {
    try {
      const bridge = window.Native;
      if (!bridge || typeof bridge.sanitizeMemoryText !== 'function') return null;
      const redacted = bridge.sanitizeMemoryText(value);
      if (typeof redacted !== 'string' || redacted.length > MAX_INPUT
        || /^\[综合记忆(?:无法安全读取|处理超出可用内存)/.test(redacted)) return null;
      return redacted;
    } catch {
      return null;
    }
  }

  function safeMessage(value) {
    if (!isScalar(value)) return GENERIC;
    let text = String(value).trim();
    if (!text || text.length > MAX_INPUT) return GENERIC;
    text = redactConfiguredKey(text);
    if (text === null) return GENERIC;

    // Structured request snapshots and response bodies do not belong in a
    // copied diagnostic. Keep only any preceding short explanation.
    if (/^[\[{]/.test(text) || /<(?:!doctype|html|body|script)\b/i.test(text)) return GENERIC;
    const requestJson = /\b(?:request(?:[_ -]?(?:body|headers|payload))?|body|headers)\b["']?\s*[:=]\s*[\[{]/i.exec(text);
    const embeddedJson = /\{\s*(?:["']|[A-Za-z_$][\w$-]*\s*:)|\[\s*(?:["'{\[]|[+-]?\d|(?:true|false|null)\b)/i.exec(text);
    const cutoff = Math.min(requestJson ? requestJson.index : Infinity, embeddedJson ? embeddedJson.index : Infinity);
    if (Number.isFinite(cutoff)) text = text.slice(0, cutoff).trim() + ' [请求内容已隐藏]';

    // Normalize escaped slashes before removing links; URLs can carry signed
    // query strings, user info and credentials even inside an error message.
    text = text.replace(/\\\//g, '/');
    // Base64 attachments may wrap across lines. Hide the entire continuous
    // encoded block before the generic URI rule stops at whitespace.
    text = text.replace(/\bdata:[^,\s"'<>]*;base64,[A-Za-z0-9+\/_=\s-]+/gi, '[数据链接已隐藏]');
    text = text.replace(/\bdata:[^\s"'<>]+/gi, '[数据链接已隐藏]');
    text = text.replace(/\b[a-z][a-z0-9+.-]*:\/\/[^\s"'<>]+/gi, '[链接已隐藏]');
    text = text.replace(/\b(?:https?|ftp)(?:%3a|:)(?:%2f|\/){2}[^\s"'<>]+/gi, '[链接已隐藏]');
    text = text.replace(/\/\/[^\s"'<>]+/g, '[链接已隐藏]');
    text = text.replace(/\b(?:mailto|tel|urn|mm_file|file|blob):[^\s"'<>]+/gi, '[链接已隐藏]');
    text = text.replace(/\bwww\.[^\s"'<>]+/gi, '[链接已隐藏]');
    text = text.replace(/\b(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z]{2,24}(?::[0-9]{1,5})?[/?#][^\s"'<>]*/g, '[链接已隐藏]');
    text = text.replace(/\b(?:Bearer|Basic)\s+[A-Za-z0-9._~+\/-]+=*/gi, '[认证信息已隐藏]');
    text = text.replace(/\bsk-[A-Za-z0-9_-]+/gi, '[密钥已隐藏]');
    text = text.replace(/\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b/g, '[认证信息已隐藏]');
    // Cookies have multiple semicolon-separated values, so remove the line.
    text = text.replace(/\b(?:set-cookie|cookie)["']?\s*[:=][^\r\n]*/gi, '[Cookie 已隐藏]');
    text = text.replace(/\b(?:authorization|proxy-authorization|api[_ -]?key|x-api-key|x-goog-api-key|(?:access[_ -]?|refresh[_ -]?|id[_ -]?)token|secret|password|passwd|credentials?|access[_ -]?key|signature|x-amz-[A-Za-z-]+|session(?:id)?)["']?\s*[:=]\s*(?:"(?:\\.|[^"\\\r\n])*"|'(?:\\.|[^'\\\r\n])*'|[^,;\r\n]+)/gi, '[敏感字段已隐藏]');
    text = text.replace(/[\u0000-\u001f\u007f]+/g, ' ').replace(/\s+/g, ' ').trim();
    return text ? text.slice(0, MAX_MESSAGE) : GENERIC;
  }

  function safeCode(value) {
    if (!isScalar(value)) return '';
    const text = safeMessage(value);
    return text !== GENERIC && /^[A-Za-z0-9][A-Za-z0-9_.:+-]{0,127}$/.test(text) ? text : '';
  }

  const MESSAGE_PATHS = [
    'error.message', 'error.reason', 'data.error.message', 'data.error.reason',
    'task.error.message', 'data.task.error.message', 'data.data.error.message',
    'base_resp.status_msg', 'data.base_resp.status_msg',
    'fail_reason', 'data.fail_reason', 'reason', 'data.reason',
    'task.message', 'data.task.message', 'data.data.output.message',
    'message', 'msg', 'data.message', 'data.msg', 'safeMessage',
    'error', 'data.error', 'task.error', 'data.task.error'
  ];
  const CODE_PATHS = [
    'error.code', 'data.error.code', 'task.error.code', 'data.task.error.code',
    'data.data.error.code', 'error.type', 'data.error.type', 'task.error.type',
    'base_resp.status_code', 'data.base_resp.status_code',
    'providerCode', 'code', 'status_code', 'data.code'
  ];

  // This function extracts fields only: code=0/success and task terminal states
  // keep their existing meaning in the caller. Provider-claimed http_code is
  // not an actual HTTP response status and is deliberately omitted here.
  function fromResponse(result) {
    if (!isRecord(result)) return {errorSource: 'response', safeMessage: GENERIC};
    const taskError = readPath(result, 'task.error') ?? readPath(result, 'data.task.error');
    const details = {
      errorSource: taskError ? 'task' : 'provider',
      safeMessage: safeMessage(firstScalar(result, MESSAGE_PATHS))
    };
    const code = safeCode(firstScalar(result, CODE_PATHS));
    if (code && !['0', 'success', 'ok', 'accepted', '20000000'].includes(code)) details.providerCode = code;
    return details;
  }

  function fromNative(raw, status) {
    let structured = isRecord(raw) ? raw : null;
    if (typeof raw === 'string' && raw.length <= MAX_INPUT) {
      try {
        const parsed = JSON.parse(raw);
        if (isRecord(parsed)) structured = parsed;
        else raw = isScalar(parsed) ? parsed : undefined;
      } catch {
        // Plain native exception messages are sanitized below; no raw body is retained.
      }
    }
    const received = Number(status);
    const hasHttpStatus = Number.isInteger(received) && received > 0 && received <= 599;
    const details = structured ? fromResponse(structured)
      : {errorSource: 'native', safeMessage: safeMessage(raw)};
    const source = structured ? firstScalar(structured, ['error.source', 'errorSource', 'source']) : undefined;
    details.errorSource = source && SOURCES.has(String(source).toLowerCase())
      ? String(source).toLowerCase() : hasHttpStatus ? 'http' : 'native_unknown';
    if (hasHttpStatus) details.httpStatus = received;
    return details;
  }

  function safeRoute(endpoint, taskId) {
    if (typeof endpoint !== 'string' || !endpoint || endpoint.length > 4096
      || /[\u0000-\u001f\u007f\\]/.test(endpoint)) return HIDDEN_ROUTE;
    let path;
    try {
      path = /^[a-z][a-z0-9+.-]*:\/\//i.test(endpoint) || endpoint.startsWith('//')
        ? new URL(endpoint, 'https://leihuo.invalid').pathname : endpoint.split(/[?#]/)[0];
    } catch {
      return HIDDEN_ROUTE;
    }
    if (!path.startsWith('/') || path.startsWith('//')) return HIDDEN_ROUTE;
    const id = isScalar(taskId) ? String(taskId) : '';
    if (id.length > 512) return HIDDEN_ROUTE;
    let encodedId;
    try { encodedId = id ? encodeURIComponent(id) : ''; }
    catch { return HIDDEN_ROUTE; }
    path = path.split('/').map(segment => id && (segment === id || segment === encodedId) ? '{task_id}' : segment).join('/');
    path = path.replace(/\$TASK_ID|\{(?:task_id|id)\}|:(?:task_id|id)(?=\/|$)/g, '{task_id}');
    path = path.replace(/\$MODEL/g, '{model}');
    path = redactConfiguredKey(path);
    if (path === null || !/^\/[A-Za-z0-9/%._~{}$:+-]*$/.test(path)
      || /\bsk-[A-Za-z0-9_-]+/i.test(path) || path.length > 512) return HIDDEN_ROUTE;
    try {
      const decoded = decodeURIComponent(path);
      const redacted = redactConfiguredKey(decoded);
      if (redacted === null || redacted !== decoded || /\bsk-[A-Za-z0-9_-]+/i.test(decoded)) return HIDDEN_ROUTE;
    } catch { return HIDDEN_ROUTE; }
    return path;
  }

  window.LeihuoErrors = Object.freeze({safeMessage, fromNative, fromResponse, safeRoute});
}());
