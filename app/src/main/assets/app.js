/* 雷火：所有数据保存在本机，网关密钥由 Android Keystore 管理。 */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const STORE = 'leihuo.chats.v1', PREFS = 'leihuo.preferences.v1', MODEL_STORE = 'leihuo.models.v1';
  const MEMORY_STORE = 'leihuo.memory.v1';
  const MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024;
  const native = window.Native;
  const pending = new Map();
  const pickedImageEvents = new Set();
  let chats = read(STORE, []), prefs = { theme: 'system', model: '', systemPrompt: '请使用用户的语言回答。', favorites: [], ...read(PREFS, {}) };
  let models = read(MODEL_STORE, []), endpointMap = {}, activeChat = null, attachments = [], activeRequest = null, activeRun = null, category = '全部';
  let pickingImages = false, imagePickerChat = null;
  let sharedMemory = {version:1,enabled:true,notes:'',entries:[],ignored:[]}, memoryReady = false, memoryLoadFailed = false, memoryDirty = false, memoryError = '', memoryVisibleLimit = 50;
  let mediaOptions = read('leihuo.media-options.v1', { duration:8, aspectRatio:'16:9', resolution:'720p', generateAudio:true, voice:'', style:'', speed:1 });
  const audioOptionsByModel=read('leihuo.audio-options.v1',{}),videoOptionsByModel=read('leihuo.video-options.v1',{});
  let config = { baseUrl: 'https://ai.leihuo.netease.com', hasKey: false }, renderTimer = null, forceScroll = true, draft = '';
  let nasFormOrigin = '';
  const esc = text => String(text ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon = name => `<svg class="icon" aria-hidden="true"><use href="#icon-${name}"/></svg>`;
  const uuid = () => window.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
  function read(key, fallback) { try { return JSON.parse(localStorage.getItem(key)) ?? fallback; } catch { return fallback; } }
  function savePrefs() { localStorage.setItem(PREFS, JSON.stringify(prefs)); }
  function ensureMessageIds(chat) {
    const used = new Set(); let changed = false;
    for (const message of chat.messages || []) {
      if (!message || typeof message !== 'object') continue;
      if (typeof message.id !== 'string' || !message.id || used.has(message.id)) { message.id=uuid(); changed=true; }
      used.add(message.id);
    }
    return changed;
  }
  function persist() {
    try {
      if (native?.saveChats) { const result = JSON.parse(native.saveChats(JSON.stringify(chats))); if (!result.ok) throw new Error(result.error); }
      else localStorage.setItem(STORE, JSON.stringify(chats));
    }
    catch { toast('手机存储空间不足，请导出对话后删除部分历史。', true); }
    syncSharedMemory(); renderHistory(); updateStorageInfo();
  }
  function loadSharedMemory() {
    try {
      if (!window.LeihuoMemory) throw new Error('综合记忆组件无法加载，请重新打开 App。');
      const value = native?.loadMemory ? JSON.parse(native.loadMemory()) : JSON.parse(localStorage.getItem(MEMORY_STORE) || '{}');
      sharedMemory = LeihuoMemory.normalize(value);
      if (value?.error) throw new Error(value.error);
      memoryReady = true;
    } catch (error) { memoryReady = true; memoryLoadFailed = true; memoryError = error.message || '综合记忆无法读取，请重新打开 App。'; toast(memoryError,true); }
  }
  function updateMemoryStatus() {
    const unavailable = !window.LeihuoMemory || memoryLoadFailed;
    $('memoryEnabledSwitch').checked = sharedMemory.enabled;
    $('memoryEnabledSwitch').disabled = unavailable;
    $('memoryNotesInput').disabled = unavailable;
    $('memoryNotesSaveButton').disabled = unavailable;
    $('memoryExportButton').disabled = unavailable;
    $('memoryClearButton').disabled = !window.LeihuoMemory;
    $('memoryStatus').textContent = memoryError || `${sharedMemory.enabled ? '已开启，所有对话共用' : '已暂停自动记录和引用'} · ${sharedMemory.entries.length} 条记忆`;
  }
  function saveSharedMemory() {
    if (!memoryReady || memoryLoadFailed) { toast('综合记忆无法读取，请重新打开 App，或在记忆设置里清空后重新建立。',true); return false; }
    try {
      const json = JSON.stringify(LeihuoMemory.exportData(sharedMemory));
      if (native?.saveMemory) { const result = JSON.parse(native.saveMemory(json)); if (!result.ok) throw new Error(result.error || '综合记忆保存失败'); }
      else localStorage.setItem(MEMORY_STORE,json);
      memoryDirty = false; memoryError = ''; updateMemoryStatus(); return true;
    } catch (error) {
      const message = error.message || '综合记忆保存失败，请导出备份后重试。';
      if (message !== memoryError) toast(message,true);
      memoryDirty = true; memoryError = message; updateMemoryStatus(); return false;
    }
  }
  function syncSharedMemory() {
    if (!memoryReady || memoryLoadFailed || !window.LeihuoMemory) return;
    try {
      const result = LeihuoMemory.ingest(sharedMemory,chats); sharedMemory = result.state;
      if (result.changed || memoryDirty) { memoryDirty = true; saveSharedMemory(); }
      else updateMemoryStatus();
      if (!$('memorySheet').hidden) renderMemory();
    } catch (error) { memoryError = error.message || '综合记忆更新失败。'; updateMemoryStatus(); toast(memoryError,true); }
  }
  function renderMemory() {
    updateMemoryStatus();
    const query = $('memorySearch').value.trim().toLowerCase();
    const entries = sharedMemory.entries.filter(entry => !query || `${entry.title}\n${entry.text}\n${entry.model}`.toLowerCase().includes(query)).sort((a,b) => b.updatedAt-a.updatedAt);
    $('memoryCount').textContent = `${sharedMemory.entries.length} 条共享记忆${query ? ` · 找到 ${entries.length} 条` : ''}`;
    $('memoryList').innerHTML = entries.slice(0,memoryVisibleLimit).map(entry => `<article class="memory-entry"><div class="memory-entry-copy"><strong class="memory-entry-title">${esc(entry.title || '对话记忆')}</strong><span class="memory-entry-meta">${entry.role==='user'?'你说的':'AI 回复'} · ${esc(new Date(entry.createdAt).toLocaleDateString())}</span><div class="memory-entry-content">${esc(entry.text)}</div></div><button type="button" data-remove-memory="${esc(entry.id)}" aria-label="删除这条记忆">${icon('trash')}</button></article>`).join('') || '<p class="list-empty">'+(query?'没有找到相关记忆。':'发送消息后，记忆会自动出现在这里。')+'</p>';
    if (entries.length > memoryVisibleLimit) $('memoryList').insertAdjacentHTML('beforeend','<button type="button" class="settings-action" data-more-memory>显示更多记忆</button>');
  }
  function openMemory() { $('memoryNotesInput').value=sharedMemory.notes; $('memorySearch').value=''; memoryVisibleLimit=50; renderMemory(); showSheet('memorySheet'); }
  function exportMemory() {
    if (memoryLoadFailed || !window.LeihuoMemory) { toast('当前记忆无法读取，请先重新打开 App。',true); return; }
    const json = JSON.stringify(LeihuoMemory.exportData(sharedMemory),null,2);
    if (native?.exportMemory) native.exportMemory(json);
    else { const url=URL.createObjectURL(new Blob([json],{type:'application/json'})),link=document.createElement('a');link.href=url;link.download='雷火综合记忆.json';link.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
  }
  function combinedSystemInstruction(run) {
    const instruction = prefs.systemPrompt.trim();
    if (!memoryReady || memoryLoadFailed || !sharedMemory.enabled || !window.LeihuoMemory) return instruction;
    const lastUser = run.chat.messages.filter(message=>message.role==='user').at(-1);
    let context = LeihuoMemory.context(sharedMemory,{chatId:run.chat.id,query:lastUser ? messageText(lastUser) : '',maxChars:10000});
    if (context && native?.sanitizeMemoryText) context = native.sanitizeMemoryText(context);
    return [instruction,context].filter(Boolean).join('\n\n');
  }
  function toast(message, error = false) {
    const el = document.createElement('div'); el.className = `toast${error ? ' error' : ''}`; el.textContent = message;
    $('toastContainer').append(el); setTimeout(() => el.remove(), 4500);
  }
  function waitForImages() {
    if (!pickingImages) return false;
    toast('正在读取图片，请稍候。'); return true;
  }
  function pickImages() {
    if (waitForImages()) return;
    if (attachments.length >= 5) { toast('每次最多添加 5 个附件。'); return; }
    if (!native?.pickImages) { $('imageInput').click(); return; }
    pickingImages = true; imagePickerChat = activeChat; closeSheets(); resizeComposer();
    toast('正在读取图片…');
    try { native.pickImages(); }
    catch { pickingImages = false; imagePickerChat = null; resizeComposer(); toast('无法打开照片选择器，请重试。', true); }
  }
  function getConfig() {
    try { if (native?.getConfig) config = JSON.parse(native.getConfig() || '{}'); } catch { /* browser preview */ }
    config.baseUrl ||= 'https://ai.leihuo.netease.com';
    $('baseUrlInput').value = config.baseUrl;
    $('nasLoginUrlInput').value = config.nasLoginUrl || '';
    updateNasSettings();
    $('connectionStatus').textContent = config.hasKey ? '密钥已加密保存在此手机' : '添加 API Key 后即可使用';
    $('connectionBanner').hidden = !!config.hasKey;
  }
  function isNasAddress(value = config.baseUrl) {
    try { const url = new URL(value); return url.protocol === 'https:' && url.pathname.replace(/\/+$/, '') === '/app/leihuo'; }
    catch { return false; }
  }
  function updateNasSettings() {
    const baseUrl = $('baseUrlInput').value.trim(), nas = isNasAddress(baseUrl);
    const origin = nas ? new URL(baseUrl).origin : '';
    const loginInput = $('nasLoginUrlInput');
    if (nas && (!loginInput.value || (nasFormOrigin && loginInput.value === `${nasFormOrigin}/`))) loginInput.value = `${origin}/`;
    nasFormOrigin = origin;
    $('nasConnectionSection').hidden = !nas;
    $('nasLoginButton').disabled = !native?.loginNas;
    $('syncInterfacesButton').hidden = nas;
    $('apiKeyLabel').textContent = nas ? 'NAS API Key' : 'API Key';
    $('apiKeyInput').placeholder = config.hasKey ? '已安全保存，留空保持当前密钥' : nas ? '输入独立的 NAS API 访问密钥' : '粘贴你的雷火 API Key';
    $('apiKeyHint').textContent = nas ? '使用 NAS 服务提供的独立访问密钥；密钥仅加密保存在这台手机上。' : '密钥仅保存在这台设备上。';
    $('baseUrlHint').textContent = nas ? '填写 NAS 设置页提供的手机 App 服务地址，保留 /app/leihuo；App 会自动追加 API 路径。' : '填写雷火网关地址，或 NAS 设置页提供的手机 App 服务地址。';
    if (nas) $('interfaceSyncStatus').textContent = '官方专用接口同步仅适用于雷火官方地址；NAS 模型使用 NAS 服务提供的接口。';
    else if ($('interfaceSyncStatus').textContent.startsWith('官方专用接口同步')) $('interfaceSyncStatus').textContent = '使用雷火官方用户中心同步专用接口，登录信息只留在官方页面。';
  }
  function saveSettingsFromForm() {
    const baseUrl = $('baseUrlInput').value.trim(), apiKey = $('apiKeyInput').value.trim();
    if (!/^https:\/\//i.test(baseUrl)) throw new Error('请填写 https:// 开头的雷火网关或 NAS 地址。');
    const nas = isNasAddress(baseUrl), nasLoginUrl = $('nasLoginUrlInput').value.trim();
    if (native) {
      const result = JSON.parse(native.saveConfig(JSON.stringify({baseUrl,...(apiKey?{apiKey}:{}),...(nas?{nasLoginUrl}:{})})));
      if (!result.ok) throw new Error(result.error || '保存失败');
    } else config = {...config,baseUrl,hasKey:!!apiKey || !!config.hasKey,nasSessionEnabled:nas,...(nas?{nasLoginUrl}:{})};
    prefs.theme = $('themeSelect').value; prefs.systemPrompt = $('systemPromptInput').value;
    savePrefs(); applyTheme(); $('apiKeyInput').value = ''; getConfig();
  }
  function loginNas() {
    if (!native?.loginNas) { toast('请在支持 NAS 登录的手机安装版中使用此功能。', true); return; }
    try {
      saveSettingsFromForm();
      if (!config.nasSessionEnabled) throw new Error('请填写并保存以 /app/leihuo 结尾的 NAS HTTPS 服务地址。');
      $('nasLoginButton').disabled = true;
      $('nasLoginStatus').textContent = '设置已保存，正在打开 NAS 登录页面…';
      native.loginNas();
    } catch (error) { $('nasLoginButton').disabled = false; toast(error.message, true); }
  }
  function applyTheme() {
    const dark = prefs.theme === 'dark' || (prefs.theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
    $('themeColor').content = dark ? '#212121' : '#ffffff';
    $('themeSelect').value = prefs.theme;
    try { native?.setTheme?.(dark ? 'dark' : 'light'); } catch { /* older bridge */ }
  }
  function showSheet(id) {
    document.querySelectorAll('.sheet').forEach(el => { el.hidden = el.id !== id; });
    $('sheetBackdrop').hidden = false; document.body.classList.add('sheet-open');
    closeSidebar();
    const focus = $(id).querySelector('input:not([type=hidden]), textarea');
    if (id !== 'settingsSheet') setTimeout(() => focus?.focus(), 150);
  }
  function closeSheets() {
    document.querySelectorAll('.sheet').forEach(el => { el.hidden = true; });
    $('sheetBackdrop').hidden = true; document.body.classList.remove('sheet-open');
  }
  function closeSidebar() { document.body.classList.remove('sidebar-open'); $('sidebarBackdrop').hidden = true; $('sidebar').setAttribute('aria-hidden','true'); $('menuButton').setAttribute('aria-expanded','false'); }
  async function confirmAction(title, message) {
    $('confirmTitle').textContent = title; $('confirmMessage').textContent = message;
    const dialog = $('confirmDialog'); dialog.showModal();
    return new Promise(resolve => {
      const finish = result => { dialog.close(); cleanup(); resolve(result); };
      const yes = () => finish(true), no = () => finish(false);
      const cleanup = () => { $('confirmOkButton').removeEventListener('click', yes); $('confirmCancelButton').removeEventListener('click', no); dialog.removeEventListener('cancel', cancel); };
      const cancel = event => { event.preventDefault(); no(); };
      $('confirmOkButton').addEventListener('click', yes); $('confirmCancelButton').addEventListener('click', no); dialog.addEventListener('cancel', cancel);
    });
  }
  function request(endpoint, body = null, options = {}) {
    const id = uuid(), metadata = {};
    const timeoutMs=Math.max(1000,Math.min(600000,Number(options.timeout) || 300000));
    const promise = new Promise((resolve, reject) => {
      if (!native) { reject(new Error('请在手机安装版内连接雷火网关。')); return; }
      const entry = { resolve, reject, metadata, onData: options.onData, data: '', canceled: false, timer: null };
      entry.timer = setTimeout(() => { native?.cancel(id); pending.delete(id); reject(Object.assign(new Error('等待回复超时，请检查手机网络；视频任务可以继续查询。'),{errorSource:'client_timeout',providerCode:'CLIENT_TIMEOUT',timeoutMs})); }, timeoutMs);
      pending.set(id, entry);
      const serialized = body == null ? '' : JSON.stringify(body);
      try {
        if (native.requestWithOptions) native.requestWithOptions(id, JSON.stringify({ endpoint, method: options.method || (body == null ? 'GET' : 'POST'), body: serialized, stream: !!options.stream, headers: options.headers || {}, readTimeoutMs:timeoutMs, responseAsMedia:!!options.responseAsMedia, audioChunkStream:!!options.audioChunkStream, ...(options.audioMime ? {audioMime:options.audioMime} : {}), ...(options.multipart ? {multipart: options.multipart} : {}) }));
        else native.request(id, endpoint, serialized, !!options.stream);
      } catch (error) { clearTimeout(entry.timer); pending.delete(id); reject(error); }
    });
    return { id, promise, metadata };
  }
  function cancelRequest(id) {
    const entry = pending.get(id); if (!entry) return;
    entry.canceled = true; clearTimeout(entry.timer); native?.cancel(id); pending.delete(id);
    entry.reject(Object.assign(new Error('已停止生成'), { canceled: true }));
  }
  window.__nativeEvent = event => {
    if (typeof event === 'string') { try { event = JSON.parse(event); } catch { return; } }
    if (event.id === 'nas-login') {
      $('nasLoginButton').disabled = !native?.loginNas;
      if (event.type === 'data') {
        getConfig(); $('nasLoginStatus').textContent = '登录页面已关闭，正在刷新模型。';
        toast(event.data || '正在重新连接 NAS'); closeSheets(); refreshModels(true);
      } else if (event.type === 'error') {
        $('nasLoginStatus').textContent = event.data || '无法打开 NAS 登录页面，请检查登录地址。';
        toast($('nasLoginStatus').textContent, true);
      } else if (event.type === 'done') $('nasLoginStatus').textContent = '登录页面已关闭；完成 NAS 登录后可刷新模型。';
      return;
    }
    if (event.id === 'imagePicker') {
      try { native?.clearPickedImages?.(); } catch { /* older bridge */ }
      const pickerEventId = typeof event.pickerEventId === 'string' ? event.pickerEventId : '';
      if (pickerEventId && pickedImageEvents.has(pickerEventId)) return;
      if (pickerEventId) {
        pickedImageEvents.add(pickerEventId);
        if (pickedImageEvents.size > 100) pickedImageEvents.delete(pickedImageEvents.values().next().value);
      }
      if (event.restore === true && !pickingImages && ['data','error','done'].includes(event.type)) {
        pickingImages = true; imagePickerChat = activeChat;
      }
      if (!pickingImages || !['data','error','done'].includes(event.type)) return;
      try {
        if (event.type === 'data') {
          if (activeChat !== imagePickerChat) throw new Error('对话已切换，请在当前对话重新选择图片。');
          const result = JSON.parse(event.data || '{}');
          if (!Array.isArray(result.files)) throw new Error('图片没有读取成功，请重新选择。');
          for (const file of result.files) {
            if (attachments.length >= 5) { toast('每次最多添加 5 个附件。'); break; }
            if (file?.type !== 'image' || typeof file.name !== 'string' || typeof file.data !== 'string' || !/^data:image\/jpeg;base64,[A-Za-z0-9+/]+={0,2}$/.test(file.data)) {
              toast('图片内容无法读取，请重新选择。', true); continue;
            }
            attachments.push({type:'image',name:file.name,data:file.data});
          }
          if (Array.isArray(result.errors)) result.errors.filter(error=>typeof error==='string').forEach(error=>toast(error,true));
          renderAttachments(); closeSheets();
        } else if (event.type === 'error') toast(event.data || '图片无法读取，请重新选择。', true);
      } catch (error) { toast(error.message || '图片无法读取，请重新选择。', true); }
      finally { pickingImages = false; imagePickerChat = null; resizeComposer(); }
      return;
    }
    if (event.id === 'speech') {
      if (event.type === 'data') { $('messageInput').value = `${$('messageInput').value}${$('messageInput').value ? ' ' : ''}${event.data}`; resizeComposer(); }
      else if (event.type === 'error') toast(event.data || '无法启动手机语音输入，请使用键盘麦克风。', true);
      $('voiceButton').classList.remove('recording'); return;
    }
    if (event.id === 'import') { if (event.type === 'data') importChats(event.data); else if (event.type === 'error') toast(event.data, true); return; }
    if (event.id === 'interfaces') { if(event.type==='data'){ try { applyInterfaces(JSON.parse(event.data)); toast('接口信息已同步，现在可以继续使用模型。'); closeSheets(); }catch(error){toast(error.message,true);} }else if(event.type==='error')toast(event.data || '接口同步失败',true);return; }
    if (['media','attachment','external','export'].includes(event.id)) { if(event.type==='error')toast(event.data || '操作失败',true);else if(event.type==='data')toast(event.data || '已完成');return; }
    const entry = pending.get(event.id); if (!entry) return;
    const receivedStatus=Number(event.status);
    if(entry.metadata && Number.isInteger(receivedStatus) && receivedStatus>=100 && receivedStatus<=599)entry.metadata.httpStatus=receivedStatus;
    if (event.type === 'data') {
      entry.data = event.data;
      try { entry.onData?.(event.data, event.event); } catch (error) { clearTimeout(entry.timer); native?.cancel(event.id); pending.delete(event.id); entry.reject(error); }
    } else if (event.type === 'done' || event.type === 'error') {
      clearTimeout(entry.timer); pending.delete(event.id);
      if (event.type === 'error') entry.reject(Object.assign(new Error(humanError(event.data, event.status)), window.LeihuoErrors?.fromNative?.(event.data,event.status) || {httpStatus:Number(event.status) || undefined,errorSource:'native_unknown'}));
      else { try { entry.resolve(JSON.parse(event.data || entry.data || '{}')); } catch { entry.resolve(event.data || entry.data); } }
    }
  };
  window.__nativeBack = () => {
    if ($('confirmDialog').open) { $('confirmCancelButton').click(); return true; }
    if (!$('sheetBackdrop').hidden) { closeSheets(); return true; }
    if (document.body.classList.contains('sidebar-open')) { closeSidebar(); return true; }
    return false;
  };
  function humanError(raw, status) {
    if (isNasAddress() && (status === 401 || status === 403)) return 'NAS 拒绝了请求，请在设置中检查 NAS API Key，并重新登录 NAS。';
    if (status === 401 || status === 403) return '密钥无效或没有此模型的权限，请在设置中检查雷火 API Key。';
    if (status === 429) return '网关请求过多或额度不足，请稍后重试或检查账户额度。';
    let text = String(raw || '请求失败');
    try { const obj = JSON.parse(text); text = obj.error?.message || obj.message || text; } catch { /* text */ }
    if (/<html|<!doctype/i.test(text)) return isNasAddress() ? 'NAS 返回了登录页面，请在设置中点“登录 NAS”，完成 FN Connect 连接和飞牛登录后再刷新模型。' : `网关返回网页${status ? `（HTTP ${status}）` : ''}，请确认手机能访问雷火网络，且地址填写的是 API 网关。`;
    return providerErrorText(text).slice(0, 600).replace(/\bsk-[\w-]+/g, '[已隐藏密钥]');
  }
  function providerErrorText(message) {
    const text=String(message || '模型调用失败');
    if(/output video may be related to copyright restrictions/i.test(text))return '模型因输出视频可能涉及版权限制而拒绝生成。请使用原创角色或调整描述后再试。';
    return text;
  }
  async function refreshModels(silent = false) {
    if (!config.hasKey) { if (!silent) { showSheet('settingsSheet'); toast('先填写雷火 API Key。'); } return; }
    const buttons = [$('refreshModelsButton'), $('settingsRefreshButton')]; buttons.forEach(el => el.disabled = true);
    try {
      const data = await request('/v1/models').promise;
      if (isNasAddress() && typeof data === 'string' && /<html|<!doctype/i.test(data)) throw new Error('NAS 返回了登录页面，请在设置中点“登录 NAS”，完成 FN Connect 连接和飞牛登录后再刷新模型。');
      if (!Array.isArray(data.data) || !data.data.length) throw new Error('网关没有返回可用模型列表。');
      models = data.data.filter(model => model && typeof model.id === 'string');
      localStorage.setItem(MODEL_STORE, JSON.stringify(models)); ensureModel(); renderModels(); updateModelLabel();
      $('connectionStatus').textContent = `已连接 · ${models.length} 个模型`;
      if (!silent) toast(`已更新 ${models.length} 个模型`);
    } catch (error) { if (!silent) toast(error.message, true); $('connectionStatus').textContent = isNasAddress() ? error.message : '连接失败，请检查密钥和手机网络'; }
    finally { buttons.forEach(el => el.disabled = false); }
  }
  function applyInterfaces(value) {
    if (value?.baseUrl && value.baseUrl.replace(/\/+$/,'') !== config.baseUrl.replace(/\/+$/,'')) return;
    const routes=value?.endpoints || {}; let count=0;
    for(const [type,route] of Object.entries(routes)) {
      if(!route || typeof route.path!=='string' || !route.path.startsWith('/') || route.path.startsWith('//') || /[\r\n\\]/.test(route.path))continue;
      const method=String(route.method || 'POST').toUpperCase();if(!['GET','POST','PUT','PATCH','DELETE'].includes(method))continue;
      const previous=endpointMap[type] || {};
      const updated={...previous,type,path:route.path,method,synced:true};
      // Retain a confirmed query mapping without copying headers, bodies or
      // response selectors from interface metadata. A changed submit route
      // must not inherit the previous route's query mapping.
      if(previous.path!==route.path || Object.prototype.hasOwnProperty.call(route,'task'))delete updated.task;
      const task=route.task;
      if(task?.synced===true && String(task.method || '').toUpperCase()==='GET'
        && validSyncedTaskPath(task.path))updated.task={path:task.path,method:'GET',synced:true};
      endpointMap[type]=updated;count++;
    }
    if (!isNasAddress()) $('interfaceSyncStatus').textContent=count?`已同步 ${count} 类接口 · ${value.syncedAt ? new Date(value.syncedAt).toLocaleDateString() : '来自雷火官方页面'}`:'使用雷火官方用户中心同步专用接口，登录信息只留在官方页面。';
    resizeComposer();
  }
  function validSyncedTaskPath(path) {
    if(typeof path!=='string' || path.length>2048 || !/^\/(?!\/)/.test(path)
      || /[\s\\\u0000-\u001f\u007f#]/.test(path) || /%(?![0-9a-f]{2})/i.test(path)
      || path.split('$TASK_ID').length!==2)return false;
    try {
      const decoded=decodeURIComponent(path.split('?')[0]);
      if(decoded.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(decoded)
        || /%(?:2e|2f|5c|25)/i.test(decoded) || decoded.split('/').some(part=>part==='.' || part==='..'))return false;
      return new URL(path,'https://leihuo.invalid').origin==='https://leihuo.invalid';
    }catch{return false;}
  }
  function syncInterfaces() { if(isNasAddress($('baseUrlInput').value)){toast('专用接口同步仅适用于雷火官方地址；NAS 模型使用 NAS 服务提供的接口。');return;}if(native?.syncInterfaces)native.syncInterfaces();else toast('请在手机安装版中登录雷火并同步接口。'); }
  function videoOptions(model) { return {...(window.LeihuoVideo?.defaultsFor?.(model) || {duration:8,aspectRatio:'16:9',resolution:'720p',generateAudio:true}),...(videoOptionsByModel[model.id] || {})}; }
  function kind(model) {
    const id = model.id.toLowerCase(), endpoints = model.supported_endpoint_types || [];
    if (/embedding/.test(id) || endpoints.includes('openai-embedding')) return '向量';
    if (/3d|tripo/.test(id)) return '3D';
    if (/image|seedream|banana/.test(id) || endpoints.some(e => /image/.test(e))) return '图片';
    if (/video|seedance|veo|^minimax-h/.test(id) || endpoints.some(e => /video|seedance|h3_|^create$/.test(e))) return '视频';
    if (/tts|asr|speech|audio|voice/.test(id) || endpoints.some(e => /speech|voice|t2a/.test(e))) return '语音';
    if (endpoints.includes('typesafe-system-one') || id === 'codex-auto-review') return '专用';
    return '聊天';
  }
  function selectedModel() { return models.find(model => model.id === (activeChat?.model || prefs.model)) || { id: prefs.model || 'deepseek-v4.1-flash', supported_endpoint_types: ['openai'] }; }
  function ensureModel() {
    if (!models.some(m => m.id === prefs.model)) {
      prefs.model = ['deepseek-v4.1-flash','gemini-3.8-flash','qwen3.8-plus'].find(id => models.some(m => m.id === id)) || models.find(m => kind(m) === '聊天')?.id || models[0]?.id || '';
      savePrefs();
    }
  }
  function friendlyName(id) { return String(id).replace(/^claude-/, 'Claude ').replace(/^gpt-/, 'GPT ').replace(/^deepseek-/, 'DeepSeek ').replace(/^gemini-/, 'Gemini ').replace(/^qwen/, 'Qwen ').replace(/^doubao-/, '豆包 ').replace(/^kimi-/, 'Kimi '); }
  function updateModelLabel() { $('currentModelName').textContent = friendlyName(selectedModel().id); $('modelButton').title = selectedModel().id; }
  function renderModels() {
    const search = $('modelSearch').value.trim().toLowerCase();
    const categories = ['全部','收藏','聊天','图片','视频','语音','3D','向量','专用'];
    $('modelCategories').innerHTML = categories.map(name => `<button type="button" class="model-chip${category === name ? ' active' : ''}" data-category="${name}">${name}</button>`).join('');
    const filtered = models.filter(model => (!search || model.id.toLowerCase().includes(search) || String(model.owned_by).toLowerCase().includes(search)) && (category === '全部' || (category === '收藏' ? prefs.favorites.includes(model.id) : kind(model) === category)));
    filtered.sort((a,b) => Number(prefs.favorites.includes(b.id)) - Number(prefs.favorites.includes(a.id)) || a.id.localeCompare(b.id));
    $('modelCount').textContent = `${models.length} 个模型`;
    $('modelList').innerHTML = filtered.length ? filtered.map(model => `<div class="model-row"><button type="button" class="model-item${selectedModel().id === model.id ? ' active' : ''}" data-model="${esc(model.id)}"><span class="model-avatar">${esc(kind(model) === '聊天' ? model.id.slice(0,1).toUpperCase() : {'图片':'✦','视频':'▷','语音':'♫','3D':'◇','向量':'≋','专用':'⌘'}[kind(model)])}</span><span class="model-details"><strong>${esc(friendlyName(model.id))}</strong><small>${esc(kind(model))} · ${esc(model.owned_by || '雷火')}</small></span>${selectedModel().id === model.id ? icon('check') : ''}</button><button type="button" class="favorite-button${prefs.favorites.includes(model.id) ? ' active' : ''}" data-favorite="${esc(model.id)}" aria-label="收藏 ${esc(model.id)}">${prefs.favorites.includes(model.id) ? '★' : '☆'}</button></div>`).join('') : '<div class="list-empty">没有找到匹配的模型</div>';
  }
  function pickModel(id) {
    if (waitForImages()) return;
    if (activeRequest) { toast('请先停止当前生成，再切换模型。'); return; }
    prefs.model = id; if (activeChat) { activeChat.model = id; persist(); }
    savePrefs(); updateModelLabel(); closeSheets(); resizeComposer();
    if (kind(selectedModel()) !== '聊天') toast(`已切换到${kind(selectedModel())}模型，输入描述即可开始。`);
  }
  function renderHistory() {
    const query = $('historySearch').value.trim().toLowerCase();
    const list = chats.filter(chat => !query || chat.title.toLowerCase().includes(query) || chat.messages.some(m => m.content.toLowerCase().includes(query))).sort((a,b) => b.updatedAt - a.updatedAt);
    let previous = '';
    $('historyList').innerHTML = list.map(chat => {
      const age = (Date.now() - chat.updatedAt) / 86400000, group = age < 1 ? '今天' : age < 2 ? '昨天' : age < 7 ? '最近 7 天' : '更早';
      const heading = group !== previous ? `<div class="history-group">${group}</div>` : ''; previous = group;
      return `${heading}<div class="history-row${activeChat?.id === chat.id ? ' active' : ''}"><button class="history-item" data-chat="${esc(chat.id)}" type="button">${esc(chat.title)}</button><button class="history-delete" data-delete-chat="${esc(chat.id)}" aria-label="删除对话">${icon('trash')}</button></div>`;
    }).join('') || '<div class="list-empty">对话会保存在这里</div>';
  }
  function newChat() {
    if (waitForImages()) return;
    if (activeRequest) stopGeneration();
    activeChat = null; attachments = []; $('messageInput').value = ''; draft = ''; forceScroll = true;
    closeSidebar(); closeSheets(); renderAttachments(); renderMessages(); renderHistory(); updateModelLabel(); resizeComposer();
  }
  function openChat(id) {
    if (waitForImages()) return;
    if (activeRequest) stopGeneration(); activeChat = chats.find(chat => chat.id === id) || null;
    attachments = []; $('messageInput').value = ''; forceScroll = true; closeSidebar();
    renderAttachments(); renderMessages(); renderHistory(); updateModelLabel(); resizeComposer();
  }
  function markdown(text) {
    if (!window.marked || !window.DOMPurify) return esc(text).replace(/\n/g, '<br>');
    return DOMPurify.sanitize(marked.parse(text || '', { breaks: true, gfm: true }), { FORBID_TAGS: ['style','form','input','button','iframe'], FORBID_ATTR: ['style'], ADD_ATTR: ['target'] });
  }
  function renderMessages() {
    const messages = activeChat?.messages || []; $('emptyState').hidden = messages.length > 0;
    $('messages').innerHTML = messages.map((message,index) => {
      const media = (message.media || []).map(item => {
        const src = safeMediaUrl(item.url); if (!src) return '';
        if (item.type === 'image') return `<figure class="generated-media"><img src="${esc(src)}" alt="生成的图片" loading="lazy"><button class="media-open" data-open-media="${esc(src)}" aria-label="打开图片">打开图片</button></figure>`;
        if (item.type === 'audio') return `<audio controls preload="none" data-media-message="${esc(message.id)}" src="${esc(src)}"></audio>`;
        if (item.type === 'video') return `<video controls preload="metadata" playsinline data-media-message="${esc(message.id)}" src="${esc(src)}"></video>`;
        return `<a href="${esc(src)}" class="file-result">${esc(item.label || '打开生成结果')}</a>`;
      }).join('');
      const attached = (message.attachments || []).map(file => file.type === 'image' ? `<img class="message-attachment" src="${esc(file.data)}" alt="${esc(file.name)}">` : `<div class="message-file">${icon('file')} ${esc(file.name)}</div>`).join('');
      const thinking = message.reasoning ? `<details class="reasoning"><summary>${message.streaming ? '正在思考' : '思考过程'}</summary><div>${markdown(message.reasoning)}</div></details>` : '';
      const error = message.error ? `<div class="message-error">${esc(message.error)}</div>` : '';
      const resumable=message.task?.query?.path && message.task.status!=='failed';
      const tools = message.role === 'assistant' && !message.streaming ? `<div class="message-actions"><button data-copy-message="${index}" aria-label="复制">${icon('copy')}</button><button data-read-message="${index}" aria-label="朗读">${icon('volume')}</button>${resumable ? `<button data-resume-task="${index}" aria-label="继续查询视频">${icon('refresh')}</button>` : `<button data-regenerate="${index}" aria-label="重新生成">${icon('refresh')}</button>`}<span class="message-model">${esc(friendlyName(message.model || activeChat.model))}</span></div>` : message.role === 'user' ? `<div class="message-actions user-actions"><button data-edit-message="${index}" aria-label="编辑消息">${icon('edit')}</button><button data-copy-message="${index}" aria-label="复制">${icon('copy')}</button></div>` : '';
      const taskAction=resumable&&!message.streaming?`<button class="media-open" data-resume-task="${index}">${message.task.status==='completed'?'重新读取视频':'继续查询视频'}</button>`:'';
      const playbackError=`<div class="message-error" data-playback-error${message.playbackError?'':' hidden'}>${esc(playbackErrorText(message))}</div>`;
      const syncAction=message.requiresInterfaceSync?'<button class="media-open" data-sync-interfaces>登录雷火并同步接口</button>':'';
      return `<article class="message ${message.role}" data-message-id="${esc(message.id)}">${attached}${thinking}<div class="message-content">${message.role === 'user' ? esc(message.content).replace(/\n/g,'<br>') : markdown(message.content)}${message.streaming && !message.content && !message.reasoning ? '<span class="thinking-dot" aria-label="正在生成"></span>' : ''}</div>${media}${playbackError}${taskAction}${syncAction}${error}<div data-media-feedback>${mediaFeedbackControls(message,index)}</div>${tools}</article>`;
    }).join('');
    $('messages').querySelectorAll('pre code').forEach(code => { try { window.hljs?.highlightElement(code); } catch { /* incomplete streaming code */ } });
    if (forceScroll) requestAnimationFrame(() => { $('messageViewport').scrollTop = $('messageViewport').scrollHeight; });
    $('scrollBottomButton').hidden = forceScroll || messages.length === 0;
  }
  function scheduleRender() { if (!renderTimer) renderTimer = setTimeout(() => { renderTimer = null; renderMessages(); }, 80); }
  function safeMediaUrl(value) { return typeof value === 'string' && (/^https:\/\//i.test(value) || /^data:(image|audio|video)\//i.test(value)) ? value : ''; }
  function playbackErrorText(message) {
    if(!message.playbackError)return '';
    const label=message.playbackError.type==='video'?'视频':'音频';
    const reason=({1:'播放被中断',2:'文件读取失败',3:'文件无法解码',4:'地址或格式无法播放'})[message.playbackError.code] || '文件加载失败';
    return `${label}${reason}。${message.task?.query?.path && message.task.status!=='failed'?'可点“重新读取视频”查询原任务。':'可检查网络后重新加载媒体。'}`;
  }
  function mediaFeedbackControls(message,index) {
    if(message.role!=='assistant' || (!message.error && !message.playbackError))return '';
    const reload=message.playbackError && !(message.task?.query?.path && message.task.status!=='failed')?`<button class="media-open" data-reload-media="${index}">重新加载媒体</button>`:'';
    return `${reload}<button class="media-open" data-copy-diagnostic="${index}">复制错误信息</button>`;
  }
  function mediaPlaybackEvent(event) {
    const player=event.target;if(!player.matches?.('audio[data-media-message],video[data-media-message]'))return;
    const index=activeChat?.messages.findIndex(message=>message.id===player.dataset.mediaMessage);
    if(index==null || index<0)return;
    const message=activeChat.messages[index],article=player.closest('article');
    if(event.type!=='error' && !message.playbackError)return;
    if(event.type==='error')message.playbackError={type:player.tagName==='VIDEO'?'video':'audio',code:player.error?.code || 0};
    else delete message.playbackError;
    const error=article?.querySelector('[data-playback-error]');if(error){error.hidden=!message.playbackError;error.textContent=playbackErrorText(message);}
    const controls=article?.querySelector('[data-media-feedback]');if(controls)controls.innerHTML=mediaFeedbackControls(message,index);
    persist();
  }
  function recordGenerationError(answer,error) {
    if(error.canceled)return;
    answer.diagnostic={...(answer.diagnostic || {stage:'response'}),errorSource:error.errorSource || 'response',safeMessage:window.LeihuoErrors?.safeMessage?.(error.safeMessage || error.message) || '未返回可安全复制的错误详情。',...(error.providerCode?{providerCode:window.LeihuoErrors?.safeMessage?.(error.providerCode)}:{}),...(error.timeoutMs?{timeoutMs:error.timeoutMs}:{}),...(error.httpStatus?{httpStatus:error.httpStatus}:{})};
  }
  function copyDiagnostic(message) {
    const stages={chat:'聊天请求',submit:'提交生成',query:'查询任务',content:'读取生成文件',response:'解析返回结果'};
    const details=[`雷火 ${$('appVersion').textContent}`,`模型：${message.model || '未知'}`];
    if(message.playbackError)details.push(`阶段：${message.playbackError.type==='video'?'视频':'音频'}播放`,`播放器错误：${message.playbackError.code}`);
    else details.push(`阶段：${stages[message.diagnostic?.stage] || '解析返回结果'}`);
    const diagnostic=message.diagnostic || {};
    if(diagnostic.method)details.push(`请求方式：${diagnostic.method}`);
    const route=diagnostic.route || (diagnostic.stage==='query'?message.task?.query?.path:diagnostic.stage==='content'?message.task?.query?.contentPath:'');
    if(route)details.push(`${message.playbackError?'任务接口':'接口'}：${window.LeihuoErrors?.safeRoute?.(route,message.task?.id) || '[接口路径已隐藏]'}`);
    details.push(`${message.playbackError?'最近任务请求 HTTP':'HTTP'}：${diagnostic.httpStatus || '未记录响应状态'}`);
    if(diagnostic.errorSource)details.push(`错误来源：${diagnostic.errorSource}`);
    if(diagnostic.providerCode)details.push(`错误代码：${window.LeihuoErrors?.safeMessage?.(diagnostic.providerCode) || '[已隐藏]'}`);
    if(diagnostic.timeoutMs)details.push(`等待上限：${Math.round(diagnostic.timeoutMs/1000)} 秒`);
    if(diagnostic.retryCount)details.push(`查询重试：${diagnostic.retryCount}`);
    const cause=diagnostic.safeMessage || message.error || (message.playbackError?playbackErrorText(message):'');
    if(cause)details.push(`原因：${window.LeihuoErrors?.safeMessage?.(cause) || '未返回可安全复制的错误详情。'}`);
    if(message.task){details.push(`任务状态：${message.task.status}`);details.push(`任务编号：${/^[A-Za-z0-9._~-]{1,512}$/.test(String(message.task.id))?message.task.id:'[非标准编号已隐藏]'}`);}
    copy(details.join('\n'));
  }
  function resizeComposer() {
    const input = $('messageInput'); input.style.height = 'auto'; input.style.height = `${Math.min(input.scrollHeight, 160)}px`;
    const busy = !!activeRun || !!activeRequest, hasContent = !!input.value.trim() || !!attachments.length; $('sendButton').hidden = busy || !hasContent; $('stopButton').hidden = !busy;
    $('sendButton').disabled = !hasContent || pickingImages; $('voiceButton').hidden = hasContent || busy || pickingImages;
    ['addAttachmentButton','imageAttachButton','fileAttachButton','audioSampleButton','newChatButton','newChatTopButton'].forEach(id=>$(id).disabled=pickingImages);
    input.placeholder = kind(selectedModel()) === '聊天' ? '发送消息' : `描述你想${{'图片':'生成的图片','视频':'生成的视频','语音':'朗读的内容','3D':'生成的 3D 模型','向量':'处理的文本'}[kind(selectedModel())] || '完成的任务'}`;
    const mediaKind=kind(selectedModel()),badge=$('composerModelBadge');badge.hidden=!['语音','视频'].includes(mediaKind);
    const videoSettings=videoOptions(selectedModel());
    badge.textContent=mediaKind==='视频'?`${videoSettings.duration} 秒 · ${videoSettings.resolution}`:window.LeihuoAudio?.isInputModel?.(selectedModel())?'音频设置':'声音设置';
    if(mediaKind==='语音' && window.LeihuoAudio?.isInputModel?.(selectedModel()))input.placeholder='上传音频后发送消息';
    if(pickingImages)input.placeholder='正在读取图片…';
  }
  function renderAttachments() {
    $('attachmentPreview').hidden = attachments.length === 0;
    $('attachmentPreview').innerHTML = attachments.map((file,index) => `<div class="attachment-card">${file.type === 'image' ? `<img src="${esc(file.data)}" alt="">` : icon('file')}<span>${esc(file.name)}</span><button type="button" data-remove-attachment="${index}" aria-label="移除附件">×</button></div>`).join(''); resizeComposer();
  }
  async function addFiles(files) {
    for (const file of Array.from(files)) {
      if (attachments.length >= 5) { toast('每次最多添加 5 个附件。'); break; }
      if (file.size > MAX_ATTACHMENT_BYTES) { toast(`${file.name} 超过 15 MB，请选择更小的文件。`, true); continue; }
      try {
        if (file.type.startsWith('audio/') || /\.(wav|mp3|m4a|ogg|flac|aac|webm)$/i.test(file.name)) {
          const data=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=reject;reader.readAsDataURL(file);});
          const format=file.name.split('.').at(-1).toLowerCase();attachments.push({type:'audio',name:file.name,data,mime:file.type || `audio/${format==='mp3'?'mpeg':format}`,format});
        } else if (file.type.startsWith('image/') || /\.(jpe?g|png|webp|gif|bmp|avif|heic|heif)$/i.test(file.name)) {
          const data = await imageData(file); attachments.push({type:'image', name:file.name, data});
        } else if (/\.pdf$/i.test(file.name)) {
          if (!window.pdfjsLib) throw new Error('PDF 解析组件不可用。');
          pdfjsLib.GlobalWorkerOptions.workerSrc = 'vendor/pdf.worker.min.js';
          const pdf = await pdfjsLib.getDocument({data: new Uint8Array(await file.arrayBuffer()),cMapUrl:'vendor/cmaps/',cMapPacked:true,standardFontDataUrl:'vendor/standard_fonts/'}).promise;
          let text = ''; for (let page = 1; page <= Math.min(pdf.numPages,100); page++) { const content = await (await pdf.getPage(page)).getTextContent(); text += `\n[第 ${page} 页]\n${content.items.map(item => item.str).join(' ')}\n`; if (text.length > 200000) break; }
          await pdf.destroy(); if (!text.trim()) throw new Error('这个 PDF 没有可提取的文字，请上传截图。');
          attachments.push({type:'file', name:file.name, text:text.slice(0,200000)});
        } else if (/\.docx$/i.test(file.name)) {
          const result = await mammoth.extractRawText({arrayBuffer:await file.arrayBuffer()});
          attachments.push({type:'file', name:file.name, text:result.value.slice(0,200000)});
        } else if (/\.(txt|md|csv|json|log|js|ts|py|java|html|css|xml|yaml|yml|c|cpp|h)$/i.test(file.name) || file.type.startsWith('text/')) {
          attachments.push({type:'file',name:file.name,text:(await file.text()).slice(0,200000)});
        } else throw new Error('支持音频、图片、PDF、Word（.docx）和文本文件。');
      } catch (error) { toast(`${file.name}：${error.message}`, true); }
    }
    renderAttachments(); closeSheets();
  }
  async function imageData(file) {
    const src = await new Promise((resolve,reject) => {
      const reader = new FileReader();
      reader.onload = () => typeof reader.result === 'string' && reader.result.length ? resolve(reader.result) : reject(new Error('没有读取到图片，请重新选择。'));
      reader.onerror = () => reject(new Error('图片无法读取，请将图片保存到手机后重新选择。'));
      reader.onabort = () => reject(new Error('图片读取已取消，请重新选择。'));
      reader.readAsDataURL(file);
    });
    const img = await new Promise((resolve,reject) => {
      const image = new Image();
      image.onload = () => image.naturalWidth && image.naturalHeight ? resolve(image) : reject(new Error('图片内容为空，请重新选择。'));
      image.onerror = () => reject(new Error('手机暂不支持这张图片的格式，请转换为 JPG 或 PNG 后重试。'));
      image.src = src;
    });
    const scale = Math.min(1,1600 / Math.max(img.width,img.height)), canvas = document.createElement('canvas'); canvas.width = Math.round(img.width*scale); canvas.height = Math.round(img.height*scale);
    const context = canvas.getContext('2d');
    if (!context) throw new Error('图片处理失败，请重新选择。');
    context.drawImage(img,0,0,canvas.width,canvas.height); return canvas.toDataURL('image/jpeg',0.86);
  }
  function messageText(message) {
    return message.content + (message.attachments || []).filter(f=>f.type==='file').map(file => `\n\n附件「${file.name}」：\n${file.text}`).join('');
  }
  function historyPayload(protocol, instruction = prefs.systemPrompt.trim()) {
    const messages = activeChat.messages.filter(m => m.role === 'user' || (m.role === 'assistant' && m.content && !m.error && !m.media?.length)).filter(m => !m.streaming);
    if (protocol === 'anthropic') return messages.map(m => ({role:m.role,content:[{type:'text',text:messageText(m) || '请分析附件。'},...(m.attachments || []).filter(f=>f.type==='image').map(f=>({type:'image',source:{type:'base64',media_type:'image/jpeg',data:f.data.split(',')[1]}}))]}));
    if (protocol === 'responses') return messages.map(m=>({role:m.role,content:[{type:m.role==='assistant'?'output_text':'input_text',text:messageText(m) || '请分析附件。'},...(m.attachments||[]).filter(f=>f.type==='image').map(f=>({type:'input_image',image_url:f.data}))]}));
    if (protocol === 'gemini') return messages.map(m=>({role:m.role==='assistant'?'model':'user',parts:[{text:messageText(m) || '请分析附件。'},...(m.attachments||[]).filter(f=>f.type==='image').map(f=>({inlineData:{mimeType:'image/jpeg',data:f.data.split(',')[1]}}))]}));
    const result = messages.map(m=>({role:m.role,content:(m.attachments||[]).some(f=>f.type==='image') ? [{type:'text',text:messageText(m) || '请分析附件。'},...(m.attachments||[]).filter(f=>f.type==='image').map(f=>({type:'image_url',image_url:{url:f.data}}))] : messageText(m)}));
    if (instruction) result.unshift({role:'system',content:instruction}); return result;
  }
  function chatRoute(model) {
    const endpoints = model.supported_endpoint_types || [];
    if (endpoints.includes('openai')) return 'openai';
    if (endpoints.includes('openai-response') || /^gpt-(?:5\.6|6)/i.test(model.id)) return 'responses';
    if (endpoints.includes('anthropic')) return 'anthropic';
    if (endpoints.includes('gemini')) return 'gemini';
    return 'openai';
  }
  function streamDelta(raw, protocol, answer) {
    if (!raw || raw === '[DONE]') return; let event; try { event = JSON.parse(raw); } catch { return; }
    if (event.error) throw new Error(event.error.message || JSON.stringify(event.error));
    if (protocol === 'openai') {
      const delta = event.choices?.[0]?.delta;
      if (delta) { answer.content += typeof delta.content === 'string' ? delta.content : Array.isArray(delta.content) ? delta.content.map(p=>p.text||'').join('') : ''; answer.reasoning += delta.reasoning_content || delta.reasoning || ''; }
    } else if (protocol === 'responses') {
      if (event.type === 'response.output_text.delta') answer.content += event.delta || '';
      if (/^response\.reasoning(?:_summary)?_text\.delta$/.test(event.type)) answer.reasoning += event.delta || '';
      if (event.type === 'response.completed' && !answer.content) answer.content = (event.response?.output || []).flatMap(o=>o.content || []).filter(c=>c.type==='output_text').map(c=>c.text).join('');
      if (event.type === 'response.failed' || event.type === 'error') throw new Error(event.response?.error?.message || event.message || '模型生成失败');
    } else if (protocol === 'anthropic') {
      if (event.type === 'content_block_delta') { answer.content += event.delta?.text || ''; answer.reasoning += event.delta?.thinking || ''; }
    } else {
      (event.candidates?.[0]?.content?.parts || []).forEach(part => { if (part.text) { if (part.thought) answer.reasoning += part.text; else answer.content += part.text; } if (part.inlineData) answer.media.push({type:'image',url:`data:${part.inlineData.mimeType};base64,${part.inlineData.data}`}); });
    }
    scheduleRender();
  }
  function ensureRun(run) { if(run?.canceled)throw Object.assign(new Error('已停止等待'),{canceled:true}); }
  async function awaitRequest(call,run) {
    ensureRun(run);if(activeRun===run){activeRequest=call.id;resizeComposer();}
    try {
      const result=await call.promise;ensureRun(run);
      if(run?.answer && call.metadata?.httpStatus)run.answer.diagnostic={...(run.answer.diagnostic || {}),httpStatus:call.metadata.httpStatus};
      return result;
    } catch(error) {
      if(!error.httpStatus && call.metadata?.httpStatus)error.httpStatus=call.metadata.httpStatus;
      throw error;
    }
  }
  async function generateChat(model, answer,run) {
    const protocol = chatRoute(model), instruction = combinedSystemInstruction(run); let endpoint, body, headers = {};
    if (protocol === 'responses') { endpoint='/v1/responses'; body={model:model.id,input:historyPayload(protocol,instruction),stream:true}; if(instruction)body.instructions=instruction; }
    else if (protocol === 'anthropic') { endpoint='/v1/messages'; body={model:model.id,messages:historyPayload(protocol,instruction),max_tokens:8192,stream:true}; if(instruction)body.system=instruction; headers={'anthropic-version':'2023-06-01'}; }
    else if (protocol === 'gemini') { endpoint=`/v1beta/models/${encodeURIComponent(model.id)}:streamGenerateContent?alt=sse`; body={contents:historyPayload(protocol,instruction)}; if(instruction)body.systemInstruction={parts:[{text:instruction}]}; }
    else { endpoint='/v1/chat/completions'; body={model:model.id,messages:historyPayload(protocol,instruction),stream:true}; }
    const call = request(endpoint,body,{stream:true,headers,onData:raw=>streamDelta(raw,protocol,answer)});
    const result = await awaitRequest(call,run);
    if (!answer.content && !answer.media.length && result && typeof result === 'object') {
      answer.content = result.choices?.[0]?.message?.content || result.content?.map(p=>p.text||'').join('') || result.output?.flatMap(o=>o.content||[]).map(p=>p.text||'').join('') || '';
    }
    if (!answer.content && !answer.reasoning && !answer.media.length) throw new Error('模型未返回内容。请重试，或在模型工具中检查接口。');
  }
  function findTemplate(model) {
    const routes = (model.supported_endpoint_types || []).map(type=>endpointMap[type]).filter(Boolean);
    const preferred = routes.find(route => route.primary !== false && !/fetch|query|delete|upload|edit|clone|context|task$/.test(route.type || ''));
    return preferred || routes[0] || null;
  }
  function mediaTemplate(model, prompt, mediaFiles) {
    const types = model.supported_endpoint_types || [], category = kind(model), allFiles = mediaFiles ?? activeChat?.messages.filter(m=>m.role==='user').at(-1)?.attachments ?? [], files=allFiles.filter(file=>file.type==='image'), image = files[0]?.data;
    if(category==='语音')return window.LeihuoAudio?.template(model,prompt,allFiles,audioOptionsByModel[model.id] || {},endpointMap) || null;
    if(category==='视频' && window.LeihuoVideo){const video=LeihuoVideo.template(model,prompt,allFiles,videoOptions(model),endpointMap);if(video)return video;}
    if(category==='图片' && allFiles.some(file=>file.type!=='image'))throw new Error('图片生成目前只支持照片附件，请移除其他文件后发送；输入内容已保留。');
    if (image && types.includes('openai-image-edit-endpoint')) {
      const body={model:model.id,prompt,n:1,size:'1024x1024'};
      return {endpoint:'/v1/images/edits',body,multipart:{fields:Object.fromEntries(Object.entries(body).map(([key,value])=>[key,String(value)])),files:files.map((file,index)=>({field:files.length>1?'image[]':'image',name:`image-${index}.jpg`,mime:'image/jpeg',base64:file.data.split(',')[1]}))}};
    }
    if (category === '图片' && types.includes('gemini')) return {endpoint:`/v1beta/models/${encodeURIComponent(model.id)}:generateContent`,body:{contents:[{role:'user',parts:[{text:prompt},...files.map(file=>({inlineData:{mimeType:'image/jpeg',data:file.data.split(',')[1]}}))]}],generationConfig:{responseModalities:['TEXT','IMAGE']}}};
    if (types.includes('openai-image-generation') || types.includes('image-generation')) {
      const seedream=/seedream/i.test(model.id),qwen=/^qwen-image/i.test(model.id);
      if(files.length && !seedream && !qwen)throw new Error('这个图片模型尚未接通图片编辑，请选择支持图片编辑的模型；附件已保留。');
      return {endpoint:'/v1/images/generations',body:{model:model.id,prompt,n:1,...(/seedream-(4-5|5-0)/i.test(model.id)?{size:'2048x2048'}:/^gpt-image/i.test(model.id)?{size:'1024x1024'}:{}),...(seedream&&files.length?{image:files.length===1?image:files.map(file=>file.data)}:{}),...(qwen&&files.length?{input:{messages:[{role:'user',content:[...files.map(file=>({image:file.data})),{text:prompt}]}]}}:{})}};
    }
    if (category === '图片' && types.includes('openai')) return {endpoint:'/v1/chat/completions',body:{model:model.id,messages:[{role:'user',content:files.length?[{type:'text',text:prompt},...files.map(file=>({type:'image_url',image_url:{url:file.data}}))]:prompt}],stream:false}};
    if (/asr|voiceclone|voicedesign/i.test(model.id) && types.includes('openai')) return null;
    if (types.includes('openai-embedding')) return {endpoint:'/v1/embeddings',body:{model:model.id,input:prompt}};
    const template = findTemplate(model);
    if (template?.body) {
      let body = JSON.parse(JSON.stringify(template.body));
      body = substitute(body, {'$MODEL':model.id,'$PROMPT':prompt,'$IMAGE':image || ''});
      return {endpoint:substitute(template.path,{'$MODEL':encodeURIComponent(model.id)}),method:template.method || 'POST',headers:substitute(template.headers || {}, {'$MODEL':model.id}),body,task:template.task};
    }
    return null;
  }
  function substitute(value, vars) {
    if (typeof value === 'string') { for (const [key, replacement] of Object.entries(vars)) value = value.split(key).join(replacement); return value; }
    if (Array.isArray(value)) return value.map(v=>substitute(v,vars));
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,substitute(v,vars)])); return value;
  }
  function readResultPath(result,path) { return path.split('.').reduce((value,key)=>value?.[key],result); }
  function mediaType(mime) { return /^(?:image|audio|video)\//i.test(mime || '') ? String(mime).split('/')[0].toLowerCase() : ''; }
  function urlMediaType(value) {
    const inline=/^data:(image|audio|video)\//i.exec(value);
    if(inline)return inline[1].toLowerCase();
    let path;try{path=new URL(value).pathname;}catch{return '';}
    if(/\.(?:png|jpe?g|webp|gif|avif|bmp|svg)$/i.test(path))return 'image';
    if(/\.(?:mp3|wav|ogg|oga|opus|m4a|aac|flac)$/i.test(path))return 'audio';
    if(/\.(?:mp4|webm|mov|m4v)$/i.test(path))return 'video';
    if(/\.(?:glb|gltf|obj|fbx|zip)$/i.test(path))return 'file';
    return '';
  }
  function extractMedia(result, preferredKind = '', task = null) {
    const media = [], seen = new Set();
    const add = (type,url,label) => { if (type && safeMediaUrl(url) && !seen.has(url)) { seen.add(url); media.push({type,url,label}); } };
    if (result?.__binary) add(mediaType(result.mime),`data:${result.mime};base64,${result.base64}`);
    if (result?.__localMedia) add(mediaType(result.mime),result.url);
    if (result?.__remoteMedia && result.type==='video') add('video',result.url);
    if(preferredKind==='视频' && task) {
      // Task results are parsed from their confirmed output fields. Never walk
      // input images, covers or request URLs looking for a video.
      const paths=[...(task.mediaPaths || []),'content.video_url','data.content.video_url','output.video_url','data.output.video_url'];
      for(const path of paths) {
        const value=readResultPath(result,path);
        for(const url of Array.isArray(value)?value:[value]) {
          if(typeof url!=='string' || !safeMediaUrl(url))continue;
          const detected=urlMediaType(url);
          if(!detected || detected==='video')add('video',url);
        }
      }
      return media.filter(item=>item.type==='video');
    }
    const walk = (value,key='',depth=0,context='') => {
      if (depth>14 || !value) return;
      if (typeof value === 'string') {
        if (safeMediaUrl(value)) {
          const detected=urlMediaType(value);
          if(detected)add(detected,value,detected==='file'?'打开 3D 模型':undefined);
          else if(/audio|speech|voice/.test(key) || context==='audio')add('audio',value);
          else if(/video/.test(key) || context==='video')add('video',value);
          else if(/^(?:url|uri|image|image_url|file|file_url|output|data|result_url)$/.test(key))add(preferredKind==='视频'?'video':preferredKind==='语音'?'audio':'image',value);
        }
        return;
      }
      if (Array.isArray(value)) { value.forEach(v=>walk(v,key,depth+1,context)); return; }
      if (value.b64_json) add('image',`data:image/png;base64,${value.b64_json}`);
      if (value.inlineData?.data) add(mediaType(value.inlineData.mimeType),`data:${value.inlineData.mimeType};base64,${value.inlineData.data}`);
      if (value.image_url?.url) add('image',value.image_url.url);
      if(value.audio?.data && typeof value.audio.data==='string')add('audio',`data:audio/${value.audio.format==='mp3'?'mpeg':value.audio.format || 'wav'};base64,${value.audio.data}`);
      if(typeof value.audio==='string') {
        if(safeMediaUrl(value.audio))add('audio',value.audio);
        else if(/^(?:[0-9a-f]{2})+$/i.test(value.audio) && value.audio.length>100) {
          const bytes=value.audio.match(/.{2}/g).map(h=>String.fromCharCode(parseInt(h,16))).join('');add('audio',`data:audio/mpeg;base64,${btoa(bytes)}`);
        } else if(value.audio.length>100 && /^[A-Za-z0-9+/]+={0,2}$/.test(value.audio))add('audio',`data:audio/mpeg;base64,${value.audio}`);
      }
      Object.entries(value).forEach(([k,v])=> {
        if(/^(?:input|request|request_body|prompt|thumbnail(?:_url)?|cover(?:_url)?|poster(?:_url)?|avatar(?:_url)?)$/i.test(k))return;
        if(['b64_json','base64','audio'].includes(k) && typeof v==='string')return;
        walk(v,k,depth+1,/video/i.test(k)?'video':/audio|speech|voice/i.test(k)?'audio':context);
      });
    }; walk(result);
    return preferredKind==='视频'?media.filter(item=>item.type==='video'):media;
  }
  function mediaError(result) {
    if(!result || typeof result!=='object')return '';
    for(const error of [result.error,result.data?.error]) {
      if(typeof error==='string' && error.trim())return error;
      if(error && typeof error==='object') {
        if(typeof error.message==='string' && error.message.trim())return error.message;
        if(error.code!=null && error.code!=='' && ![0,'0','success','ok'].includes(error.code))return `接口返回 ${String(error.code)}`;
        if(typeof error.type==='string' && error.type.trim())return `模型返回 ${error.type}`;
      }
      if(error===true)return '模型调用失败';
    }
    if(result.base_resp?.status_code && String(result.base_resp.status_code)!=='0')return result.base_resp.status_msg || '音频生成失败';
    if(result.code != null && ![0,20000000,'success','ok','accepted'].includes(result.code))return result.message || `接口返回 ${result.code}`;
    return '';
  }
  function responseFailure(result,message,source='provider') {
    const reason=String(message || '模型调用失败');
    return Object.assign(new Error(providerErrorText(reason)),window.LeihuoErrors?.fromResponse?.(result) || {},{errorSource:source,safeMessage:window.LeihuoErrors?.safeMessage?.(reason)});
  }
  async function waitForTask(milliseconds,run) {
    ensureRun(run);
    await new Promise((resolve,reject)=>{const id=uuid();if(activeRun===run)activeRequest=id;const timer=setTimeout(()=>{pending.delete(id);resolve();},milliseconds);pending.set(id,{resolve,reject,timer});});
    ensureRun(run);
  }
  function retryableTaskRead(error,task) {
    if(error.canceled || String(task.method || 'GET').toUpperCase()!=='GET')return false;
    if(['native_tls','native_validation','native_parse'].includes(error.providerCode))return false;
    if([408,429,500,502,503,504].includes(error.httpStatus))return true;
    return ['CLIENT_TIMEOUT','native_timeout','native_dns','native_connect','native_io'].includes(error.providerCode);
  }
  function applyMediaResult(answer,result,model,task=answer.task?.query) {
    answer.media=extractMedia(result,kind(model),task);
    const parts=result.candidates?.[0]?.content?.parts || [];
    answer.content=parts.map(p=>p.text || '').join('') || result.choices?.[0]?.message?.content || result.choices?.[0]?.message?.final_text_preview || result.text || '';
    if(typeof answer.content!=='string')answer.content='';
    if(kind(model)==='视频' && !answer.media.length)throw new Error(result.__binary?'网关返回的文件无法识别为视频，任务已保留，请重新读取视频。':'任务已完成，但网关没有返回可播放的视频地址，请继续查询或检查雷火后台任务。');
    if(!answer.media.length && !answer.content) {
      if(kind(model)==='语音')throw new Error('模型没有返回音频或转写文本，请检查声音和参考音频是否符合要求。');
      if(result.__binary)throw new Error('网关返回的文件类型无法识别，请复制错误信息反馈。');
      answer.content='```json\n'+JSON.stringify(result,null,2).slice(0,30000)+'\n```';
    }
    if(answer.task)answer.task.status='completed';
    delete answer.incomplete;delete answer.playbackError;answer.error='';
    answer.rawResult=result.__binary?undefined:trimRawResult(result);
  }
  async function generateMedia(model, answer, prompt, overrides,run) {
    const template = overrides || mediaTemplate(model,prompt);
    if (!template) {
      answer.requiresInterfaceSync=true;
      throw new Error('此模型的专用接口尚未同步，请先登录雷火用户中心同步接口。');
    }
    if(template.task)template.task=window.LeihuoVideo?.normalizeTask?.(template.task) || template.task;
    if(template.multipart)template.multipart.fields=Object.fromEntries(Object.entries(template.body).map(([key,value])=>[key,typeof value==='string'?value:JSON.stringify(value)]));
    answer.diagnostic={stage:'submit',method:template.method || 'POST',route:window.LeihuoErrors?.safeRoute?.(template.endpoint)};
    const call = request(template.endpoint,template.body,{method:template.method,headers:template.headers,multipart:template.multipart,responseAsMedia:true,audioChunkStream:template.audioChunkStream,audioMime:template.audioMime,timeout:600000});
    let result = await awaitRequest(call,run);
    if (template.task) result = await pollTask(template, result, answer, model,run);
    else {const error=mediaError(result);if(error)throw responseFailure(result,error);}
    applyMediaResult(answer,result,model,template.task);
  }
  function trimRawResult(result) { const json = JSON.stringify(result); return json.length<50000 ? result : undefined; }
  async function pollTask(template, result, answer, model,run) {
    const task = window.LeihuoVideo?.normalizeTask?.(template.task) || template.task;
    const rawId=answer.task?.id || (task.idPaths || ['id','task_id','data.task_id','data.id']).map(path=>readResultPath(result,path)).find(value=>(typeof value==='string' && value.trim()) || (typeof value==='number' && Number.isFinite(value)));
    const taskId=rawId==null?'':String(rawId).trim();
    if (!taskId) { const error=mediaError(result);if(error)throw responseFailure(result,error);if(extractMedia(result,kind(model),task).length)return result;throw new Error('网关未返回视频任务编号，未重复提交。'); }
    if(taskId.length>512 || /[\u0000-\u001f\u007f]/.test(taskId))throw new Error('网关返回的任务编号格式无效，未重复提交。');
    let queryNow=!!answer.task;
    if(!answer.task)answer.task={id:taskId,model:model.id,status:'pending',baseUrl:config.baseUrl,submittedAt:Date.now(),query:task,headers:template.headers || {}};
    else answer.task.query=task;
    persist();
    const deadline=Date.now()+20*60*1000;
    let transientRetries=0;
    while(Date.now()<deadline) {
      ensureRun(run);
      let queried=false;
      if(queryNow) {
        const endpoint=substitute(task.path,{'$TASK_ID':encodeURIComponent(taskId),'$MODEL':encodeURIComponent(model.id)});
        answer.diagnostic={stage:'query',method:task.method || 'GET',route:window.LeihuoErrors?.safeRoute?.(task.path,taskId),retryCount:transientRetries};
        const call=request(endpoint,task.body ? substitute(task.body,{'$TASK_ID':taskId,'$MODEL':model.id}):null,{method:task.method || 'GET',headers:substitute(task.headers || template.headers || {},{'$MODEL':model.id}),timeout:Math.min(90000,60000+transientRetries*15000,Math.max(1000,deadline-Date.now()))});
        try {result=await awaitRequest(call,run);queryNow=false;transientRetries=0;queried=true;}
        catch(error) {
          if(!retryableTaskRead(error,task) || transientRetries>=3 || Date.now()>=deadline)throw error;
          transientRetries++;
          answer.task.status='pending';answer.content=`正在重新连接视频任务（${transientRetries}/3）…`;
          scheduleRender();
          await waitForTask(Math.min([2000,5000,10000][transientRetries-1],Math.max(1,deadline-Date.now())),run);
          continue;
        }
      }
      if(queried && (!result || typeof result!=='object' || Array.isArray(result)))throw Object.assign(new Error('任务查询没有返回有效的 JSON，请检查手机网络和网关地址。'),{errorSource:'response',providerCode:'QUERY_RESPONSE_INVALID'});
      const state=String((task.statusPaths || ['data.status','status','data.state','state']).map(path=>readResultPath(result,path)).find(Boolean) || '').trim().toLowerCase();
      if(['failed','failure','error','canceled','cancelled','expired','rejected','unsuccessful'].includes(state)){answer.task.status='failed';const reason=(task.errorPaths || []).map(path=>readResultPath(result,path)).find(Boolean);throw responseFailure(result,typeof reason==='string'?reason:result.data?.fail_reason || result.fail_reason || result.message || '视频生成失败','task');}
      const error=mediaError(result);if(error)throw responseFailure(result,error);
      const ready=extractMedia(result,kind(model),task).length>0;
      if(queried && !state && !ready)throw Object.assign(new Error('查询结果缺少任务状态和视频地址，原任务编号已保留。'),{errorSource:'response',providerCode:'QUERY_STATUS_MISSING'});
      if(['succeed','succeeded','success','successful','completed','done','finish','finished'].includes(state) || (!state && ready)){
        answer.content='';
        if(!ready && task.contentPath){
          const endpoint=substitute(task.contentPath,{'$TASK_ID':encodeURIComponent(taskId),'$MODEL':encodeURIComponent(model.id)});
          answer.diagnostic={stage:'content',method:'GET',route:window.LeihuoErrors?.safeRoute?.(task.contentPath,taskId)};
          result=await awaitRequest(request(endpoint,null,{responseAsMedia:true,headers:template.headers,timeout:120000}),run);
          const contentError=mediaError(result);if(contentError)throw responseFailure(result,contentError);
        }
        return result;
      }
      const progress=readResultPath(result,'data.progress') ?? result.progress;
      answer.task.status='pending';answer.task.progress=progress;
      answer.content=/queued|submitted|not_start|pending/.test(state)?'视频已提交，正在排队…':`正在生成视频${progress?`（${String(progress).replace(/%$/,'')}%）`:'，请稍候…'}`;
      scheduleRender();
      await waitForTask(Math.min(5000,Math.max(1,deadline-Date.now())),run);
      queryNow=true;
    }
    throw new Error('视频仍在处理中，任务编号已保存，可以点击“继续查询视频”。');
  }
  async function sendMessage(event, overrides) {
    event?.preventDefault(); if (activeRequest || activeRun) return;
    if (waitForImages()) return;
    const text = $('messageInput').value.trim(); if (!text && !attachments.length) return;
    if (!config.hasKey) { showSheet('settingsSheet'); toast('填写 API Key 后即可开始对话。'); return; }
    const model = selectedModel();
    if(kind(model)!=='聊天' && !overrides){try{overrides=mediaTemplate(model,text || '请分析附件。',attachments);if(!overrides){toast('此模型需要先同步雷火接口，输入内容会保留。');showSheet('settingsSheet');return;}}catch(error){toast(error.message,true);return;}}
    if (!activeChat) { activeChat={id:uuid(),title:(text || attachments[0]?.name || '新对话').slice(0,32),model:model.id,createdAt:Date.now(),updatedAt:Date.now(),messages:[]}; chats.unshift(activeChat); }
    activeChat.messages.push({id:uuid(),role:'user',content:text,attachments:attachments.slice(),createdAt:Date.now(),model:model.id});
    $('messageInput').value=''; attachments=[]; draft=''; renderAttachments();
    await runGeneration(model, text || '请分析附件。', overrides);
  }
  async function runGeneration(model,prompt,overrides) {
    const chat = activeChat, answer={id:uuid(),role:'assistant',content:'',reasoning:'',media:[],model:model.id,streaming:true,createdAt:Date.now()}; chat.messages.push(answer); forceScroll=true;
    const run={chat,answer,canceled:false};activeRun=run;answer.diagnostic={stage:kind(model)==='聊天'?'chat':'submit',method:'POST'};
    persist(); renderMessages();
    try { if (kind(model)==='聊天' && !overrides) await generateChat(model,answer,run); else await generateMedia(model,answer,prompt,overrides,run); }
    catch (error) { recordGenerationError(answer,error);if(error.canceled)answer.incomplete=true;if(answer.task && answer.task.status!=='failed')answer.task.status='paused';if(!error.canceled)answer.error=error.message;if(answer.task)answer.content=error.canceled?'已停止等待，任务编号已保存。':answer.task.status==='failed'?'视频生成失败。':'任务编号已保存，可以继续查询。';else if(error.canceled && !answer.content && !answer.media.length)answer.content=kind(model)==='视频'?'已停止等待视频结果。':'已停止生成。'; }
    finally { answer.streaming=false; if(activeRun===run){activeRun=null;activeRequest=null;}chat.updatedAt=Date.now();persist();if(activeChat===chat)renderMessages();resizeComposer(); }
  }
  function stopGeneration() { if(activeRun)activeRun.canceled=true;const id=activeRequest;activeRun=null;activeRequest=null;if(id)cancelRequest(id);resizeComposer(); }
  async function resumeTask(index) {
    if (waitForImages()) return;
    if(activeRun || activeRequest || !activeChat)return;
    const answer=activeChat.messages[Number(index)],saved=answer?.task;if(!saved?.query?.path)return;
    if(String(saved.query.method || 'GET').toUpperCase()!=='GET'){toast('这个任务缺少已确认的读取接口，请同步雷火接口。',true);return;}
    if(saved.baseUrl && saved.baseUrl!==config.baseUrl){toast('请切回提交此任务时使用的雷火地址。',true);return;}
    const model=models.find(m=>m.id===saved.model) || {id:saved.model,supported_endpoint_types:['openai-video-gen']};
    const chat=activeChat,run={chat,answer,canceled:false};activeRun=run;answer.streaming=true;answer.error='';delete answer.playbackError;renderMessages();resizeComposer();
    try{const result=await pollTask({task:saved.query,headers:saved.headers},{task_id:saved.id,status:'pending'},answer,model,run);applyMediaResult(answer,result,model);}
    catch(error){recordGenerationError(answer,error);if(error.canceled)answer.incomplete=true;if(saved.status!=='failed')saved.status='paused';answer.content=error.canceled?'已停止等待，任务编号已保存。':saved.status==='failed'?'视频生成失败。':'任务编号已保存，可以继续查询。';if(!error.canceled)answer.error=error.message;}
    finally{answer.streaming=false;if(activeRun===run){activeRun=null;activeRequest=null;}chat.updatedAt=Date.now();persist();if(activeChat===chat)renderMessages();resizeComposer();}
  }
  async function regenerate(index) {
    if (waitForImages()) return;
    if (activeRequest || activeRun) return; if (!activeChat) return;
    const answer=activeChat.messages[Number(index)];if(answer?.task && !['completed','failed'].includes(answer.task.status)){await resumeTask(index);return;}
    if(kind(selectedModel())==='视频' && !await confirmAction('重新生成视频？','这会向雷火提交一个新的生成任务。'))return;
    activeChat.messages = activeChat.messages.slice(0,Number(index));
    const lastUser = activeChat.messages.filter(m=>m.role==='user').at(-1); if(!lastUser)return;
    await runGeneration(selectedModel(),messageText(lastUser));
  }
  function copy(text) { if(native?.copy)native.copy(text); else navigator.clipboard?.writeText(text); toast('已复制'); }
  function openExternal(url) { if(!/^https?:\/\//i.test(url))return; if(native)native.openExternal(url); else window.open(url,'_blank','noopener'); }
  function updateStorageInfo() { $('storageInfo').textContent=`${chats.length} 个对话 · 仅保存在此手机`; }
  function exportChats() {
    const json = JSON.stringify({app:'leihuo',version:1,exportedAt:new Date().toISOString(),chats},null,2);
    if(native?.exportChats)native.exportChats(json); else { const url=URL.createObjectURL(new Blob([json],{type:'application/json'})), a=document.createElement('a'); a.href=url;a.download='雷火对话.json';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000); }
  }
  function importChats(text) {
    if (waitForImages()) return;
    try {
      const value=JSON.parse(text), list=Array.isArray(value)?value:value.chats;
      if(!Array.isArray(list))throw new Error('文件格式不正确。');
      let count=0;
      for(const chat of list) {
        if(!chat || typeof chat.id!=='string' || typeof chat.title!=='string' || !Array.isArray(chat.messages) || !chat.messages.every(m=>m && ['user','assistant'].includes(m.role) && typeof m.content==='string'))continue;
        if(chats.some(c=>c.id===chat.id))continue;
        ensureMessageIds(chat);
        chats.push({...chat,createdAt:Number(chat.createdAt)||Date.now(),updatedAt:Number(chat.updatedAt)||Date.now(),messages:chat.messages.map(m=>({...m,streaming:false}))});count++;
      }
      persist();toast(`已导入 ${count} 个对话`);
    }catch(error){toast(`导入失败：${error.message}`,true);}
  }
  function openTool(model=selectedModel(),prompt='') {
    const template=mediaTemplate(model,prompt) || (()=>{const route=findTemplate(model);return route?{endpoint:substitute(route.path,{'$MODEL':encodeURIComponent(model.id)}),body:substitute(route.body||{model:'$MODEL',prompt:'$PROMPT'},{'$MODEL':model.id,'$PROMPT':prompt}),method:route.method,headers:route.headers}: {endpoint:'',body:{model:model.id,prompt}};})();
    $('toolTitle').textContent=`模型工具 · ${friendlyName(model.id)}`; $('toolModelInput').value=model.id; $('toolEndpointInput').value=template.endpoint?`${template.method||'POST'} ${template.endpoint}`:'';
    $('toolEndpointInput').placeholder=template.endpoint?'POST /v1/images/generations':'填写雷火文档中的路径，例如 POST /v1/…';
    $('toolBodyInput').value=JSON.stringify(template.body,null,2); $('toolResult').textContent='';
    $('toolRequestForm').dataset.headers=JSON.stringify(substitute(template.headers || {},{'$MODEL':model.id}));
    $('toolFileField').hidden=false;
    $('toolFileInput').accept='audio/*,image/*';
    $('toolFileField').querySelector('label').textContent='附件（可选，用于语音转录或图片编辑）';
    showSheet('toolRequestSheet');
  }
  async function runTool(event) {
    event.preventDefault(); if(!config.hasKey){showSheet('settingsSheet');return;}
    const button=$('toolRunButton');button.disabled=true;$('toolResult').textContent='正在请求…';
    try {
      const input=$('toolEndpointInput').value.trim(), match=input.match(/^(GET|POST|PUT|PATCH|DELETE)\s+(\/\S+)$/i), endpoint=match?match[2]:input, method=match?match[1].toUpperCase():'POST';
      if(!endpoint.startsWith('/'))throw new Error('请求地址必须以 / 开头，例如 POST /v1/images/generations。');
      const body=['GET','DELETE'].includes(method)&&!$('toolBodyInput').value.trim()?null:JSON.parse($('toolBodyInput').value || '{}');
      if(body && Object.hasOwn(body,'model'))body.model=$('toolModelInput').value.trim();
      let multipart;
      const file=$('toolFileInput').files[0];
      if(file){
        if(file.size>MAX_ATTACHMENT_BYTES)throw new Error('附件最多 15 MB。');
        const base64=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=reject;reader.readAsDataURL(file);});
        multipart={fields:Object.fromEntries(Object.entries(body || {}).map(([key,value])=>[key,typeof value==='string'?value:JSON.stringify(value)])),files:[{field:/images\/edits/.test(endpoint)?'image':'file',name:file.name,mime:file.type || 'application/octet-stream',base64}]};
      }
      const result=await request(endpoint,body,{method,headers:JSON.parse($('toolRequestForm').dataset.headers || '{}'),timeout:600000,multipart}).promise;
      const media=extractMedia(result,kind(models.find(m=>m.id===$('toolModelInput').value) || {id:$('toolModelInput').value}));
      $('toolResult').innerHTML=media.map(m=>m.type==='image'?`<img src="${esc(m.url)}" alt="生成结果">`:m.type==='audio'?`<audio controls src="${esc(m.url)}"></audio>`:m.type==='video'?`<video controls src="${esc(m.url)}"></video>`:`<a href="${esc(m.url)}">打开结果</a>`).join('')+`<pre>${esc(result.__binary?`已收到 ${result.mime} 文件`:JSON.stringify(result,null,2).slice(0,100000))}</pre>`;
    }catch(error){$('toolResult').textContent=error.message;}
    finally{button.disabled=false;}
  }
  function openImages() {
    const list=models.filter(m=>kind(m)==='图片');$('imageModelInput').innerHTML=list.map(m=>`<option value="${esc(m.id)}">${esc(friendlyName(m.id))}</option>`).join('');
    const chosen=selectedModel();if(kind(chosen)==='图片')$('imageModelInput').value=chosen.id;
    $('imagePromptInput').value=$('messageInput').value;updateImageOptions();showSheet('imageSheet');
  }
  function updateImageOptions() {
    const id=$('imageModelInput').value, gemini=models.find(m=>m.id===id)?.supported_endpoint_types?.includes('gemini');
    const field=$('imageSizeSelect').closest('.form-field');field.hidden=!!gemini;
    const options=$('imageSizeSelect').options;
    for(const option of options){option.disabled=/seedream-(4-5|5-0)/i.test(id)?option.value!=='2048x2048':/^gpt-image/.test(id)?!['1024x1024','1024x1536','1536x1024','auto'].includes(option.value):false;}
    if(/seedream-(4-5|5-0)/i.test(id))$('imageSizeSelect').value='2048x2048';else $('imageSizeSelect').value='1024x1024';
  }
  function openMediaSettings() {
    const model=selectedModel(),isVideo=kind(model)==='视频',profile=window.LeihuoAudio?.profileFor?.(model);
    let fields,values;
    if(isVideo){
      fields=window.LeihuoVideo?.optionsFor?.(model) || [];values=videoOptions(model);
    }else{fields=window.LeihuoAudio?.optionsFor?.(model) || [];values={...(profile?.defaults || {}),...(audioOptionsByModel[model.id] || {})};}
    $('mediaSettingsTitle').textContent=isVideo?'视频设置':profile?.label || '音频设置';
    $('mediaSettingsDescription').textContent=isVideo?'选择你想要的画面效果':profile?.requiresAudio?'添加参考音频后即可发送':profile?.kind==='seed-audio'?'输入描述即可生成音频':'输入文字即可生成音频';
    $('mediaSettingsForm').dataset.model=model.id;$('mediaSettingsForm').dataset.kind=isVideo?'video':'audio';
    $('mediaSettingsFields').innerHTML=fields.map(field=>{
      const value=values[field.key] ?? field.default ?? '',id=`media-setting-${field.key}`,label=esc(field.label);
      if(field.type==='checkbox')return `<div class="form-field setting-inline"><label for="${id}">${label}</label><input id="${id}" data-media-option="${field.key}" type="checkbox"${value?' checked':''}></div>`;
      if(field.type==='select')return `<div class="form-field"><label for="${id}">${label}</label><select id="${id}" data-media-option="${field.key}">${field.choices.map(choice=>`<option value="${esc(choice.value)}"${String(choice.value)===String(value)?' selected':''}>${esc(choice.label)}</option>`).join('')}</select></div>`;
      if(field.type==='textarea')return `<div class="form-field"><label for="${id}">${label}</label><textarea id="${id}" data-media-option="${field.key}" rows="3" placeholder="${esc(field.placeholder)}">${esc(value)}</textarea></div>`;
      return `<div class="form-field"><label for="${id}">${label}</label><input id="${id}" data-media-option="${field.key}" type="${field.type==='number'?'number':'text'}" value="${esc(value)}" placeholder="${esc(field.placeholder)}"${field.min!=null?` min="${field.min}"`:''}${field.max!=null?` max="${field.max}"`:''}${field.step!=null?` step="${field.step}"`:''}></div>`;
    }).join('') || '<p class="field-hint">此模型使用默认音频设置。</p>';
    $('audioSampleButton').hidden=isVideo || !profile?.requiresAudio;showSheet('mediaSettingsSheet');
  }
  function bind() {
    $('menuButton').onclick=()=>{document.body.classList.add('sidebar-open');$('sidebarBackdrop').hidden=false;$('sidebar').setAttribute('aria-hidden','false');$('menuButton').setAttribute('aria-expanded','true');renderHistory();};
    $('closeSidebarButton').onclick=closeSidebar;$('sidebarBackdrop').onclick=closeSidebar;
    $('newChatButton').onclick=newChat;$('newChatTopButton').onclick=newChat;
    $('modelButton').onclick=()=>{renderModels();showSheet('modelSheet');};
    const openSettings=()=>{getConfig();$('systemPromptInput').value=prefs.systemPrompt;applyTheme();showSheet('settingsSheet');};
    $('settingsButton').onclick=openSettings;$('connectionSettingsButton').onclick=openSettings;
    $('syncInterfacesButton').onclick=syncInterfaces;
    $('baseUrlInput').oninput=updateNasSettings;
    $('nasLoginButton').onclick=loginNas;
    $('composerModelBadge').onclick=openMediaSettings;
    $('audioSampleButton').onclick=()=>$('audioInput').click();
    $('mediaSettingsForm').onsubmit=event=>{event.preventDefault();const values={};$('mediaSettingsFields').querySelectorAll('[data-media-option]').forEach(input=>{values[input.dataset.mediaOption]=input.type==='checkbox'?input.checked:input.type==='number'?Number(input.value):input.value;});const id=$('mediaSettingsForm').dataset.model;if($('mediaSettingsForm').dataset.kind==='video'){values.duration=Number(values.duration);videoOptionsByModel[id]=values;localStorage.setItem('leihuo.video-options.v1',JSON.stringify(videoOptionsByModel));}else{audioOptionsByModel[id]=values;localStorage.setItem('leihuo.audio-options.v1',JSON.stringify(audioOptionsByModel));}resizeComposer();closeSheets();toast('媒体设置已保存');};
    $('sheetBackdrop').onclick=closeSheets;document.querySelectorAll('[data-close-sheet]').forEach(button=>button.onclick=closeSheets);
    $('modelSearch').oninput=renderModels;$('historySearch').oninput=renderHistory;
    $('modelCategories').onclick=e=>{const button=e.target.closest('[data-category]');if(button){category=button.dataset.category;renderModels();}};
    $('modelList').onclick=e=>{const favorite=e.target.closest('[data-favorite]');if(favorite){const id=favorite.dataset.favorite;prefs.favorites=prefs.favorites.includes(id)?prefs.favorites.filter(v=>v!==id):[...prefs.favorites,id];savePrefs();renderModels();return;}const button=e.target.closest('[data-model]');if(button)pickModel(button.dataset.model);};
    $('historyList').onclick=async e=>{if(waitForImages())return;const deletion=e.target.closest('[data-delete-chat]');if(deletion){if(await confirmAction('删除对话？','删除后无法恢复，你可以先在设置中导出。')){const id=deletion.dataset.deleteChat;if(activeChat?.id===id)newChat();chats=chats.filter(c=>c.id!==id);persist();}return;}const button=e.target.closest('[data-chat]');if(button)openChat(button.dataset.chat);};
    $('refreshModelsButton').onclick=()=>refreshModels();$('settingsRefreshButton').onclick=()=>refreshModels();
    $('settingsForm').onsubmit=async e=>{
      e.preventDefault();try{
        saveSettingsFromForm();toast('设置已保存');closeSheets();await refreshModels(true);
      }catch(error){toast(error.message,true);}
    };
    $('keyVisibilityButton').onclick=()=>{const input=$('apiKeyInput');input.type=input.type==='password'?'text':'password';};
    $('manageMemoryButton').onclick=openMemory;
    $('memoryEnabledSwitch').onchange=()=>{if(memoryLoadFailed){updateMemoryStatus();toast('综合记忆无法读取，请重新打开 App 后再试。',true);return;}sharedMemory.enabled=$('memoryEnabledSwitch').checked;memoryDirty=true;syncSharedMemory();};
    $('memorySearch').oninput=()=>{memoryVisibleLimit=50;renderMemory();};
    $('memoryNotesSaveButton').onclick=()=>{if(memoryLoadFailed){toast('综合记忆无法读取，请重新打开 App，或清空后重新建立。',true);return;}sharedMemory.notes=$('memoryNotesInput').value;sharedMemory=LeihuoMemory.normalize(sharedMemory);memoryDirty=true;if(saveSharedMemory()){ $('memoryNotesInput').value=sharedMemory.notes;toast('长期信息已保存，所有对话均可引用。');renderMemory();}};
    $('memoryExportButton').onclick=exportMemory;
    $('memoryList').onclick=async e=>{const more=e.target.closest('[data-more-memory]');if(more){memoryVisibleLimit+=50;renderMemory();return;}if(memoryLoadFailed || !window.LeihuoMemory)return;const remove=e.target.closest('[data-remove-memory]');if(!remove)return;if(await confirmAction('删除这条记忆？','删除后其他对话将不再引用这条记忆，原来的聊天记录会保留。')){sharedMemory=LeihuoMemory.remove(sharedMemory,remove.dataset.removeMemory);memoryDirty=true;saveSharedMemory();renderMemory();}};
    $('memoryClearButton').onclick=async()=>{if(!window.LeihuoMemory)return;if(await confirmAction('清空综合记忆？','这会删除全部共享记忆和长期信息，已有聊天记录会保留。清空后只自动记录新消息。')){sharedMemory=LeihuoMemory.clear(sharedMemory,chats);memoryLoadFailed=false;memoryDirty=true;if(saveSharedMemory()){ $('memoryNotesInput').value='';renderMemory();toast('综合记忆已清空');}}};
    $('themeSelect').onchange=()=>{prefs.theme=$('themeSelect').value;savePrefs();applyTheme();};
    $('messageInput').oninput=()=>{draft=$('messageInput').value;resizeComposer();};
    $('messageInput').onkeydown=e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();sendMessage();}};
    $('composerForm').onsubmit=sendMessage;$('stopButton').onclick=stopGeneration;
    $('addAttachmentButton').onclick=()=>showSheet('toolsSheet');
    $('imageAttachButton').onclick=pickImages;$('fileAttachButton').onclick=()=>{if(!waitForImages())$('fileInput').click();};
    [$('imageInput'),$('fileInput'),$('audioInput')].forEach(input=>input.onchange=()=>{const files=Array.from(input.files || []);input.value='';if(files.length)addFiles(files);});
    $('attachmentPreview').onclick=e=>{const button=e.target.closest('[data-remove-attachment]');if(button){attachments.splice(Number(button.dataset.removeAttachment),1);renderAttachments();}};
    $('imageGenerationButton').onclick=openImages;$('genericToolButton').onclick=()=>openTool();
    $('toolRequestForm').onsubmit=runTool;
    $('imageModelInput').onchange=updateImageOptions;
    $('imageForm').onsubmit=async e=>{
      e.preventDefault();if(waitForImages())return;
      const id=$('imageModelInput').value,prompt=$('imagePromptInput').value.trim();
      if(!prompt){toast('请描述你想生成的图片。');return;}if(activeRequest || activeRun){toast('请先停止当前生成。');return;}
      pickModel(id);$('messageInput').value=prompt;
      try {
        const template=mediaTemplate(selectedModel(),prompt,attachments);
        if(template?.body && /^\/v1\/images\/(?:generations|edits)$/.test(template.endpoint))template.body.size=$('imageSizeSelect').value;
        await sendMessage(null,template);
      } catch(error){toast(error.message,true);}
    };
    $('voiceButton').onclick=()=>{if(native?.speech){$('voiceButton').classList.add('recording');native.speech();}else toast('请使用手机键盘的语音输入。');};
    $('messages').onclick=async e=>{
      const resumeButton=e.target.closest('[data-resume-task]');if(resumeButton){resumeTask(resumeButton.dataset.resumeTask);return;}
      const diagnosticButton=e.target.closest('[data-copy-diagnostic]');if(diagnosticButton){copyDiagnostic(activeChat.messages[Number(diagnosticButton.dataset.copyDiagnostic)]);return;}
      const reloadButton=e.target.closest('[data-reload-media]');if(reloadButton){reloadButton.closest('article').querySelectorAll('audio,video').forEach(player=>player.load());return;}
      if(e.target.closest('[data-sync-interfaces]')){syncInterfaces();return;}
      const copyButton=e.target.closest('[data-copy-message]');if(copyButton){copy(activeChat.messages[Number(copyButton.dataset.copyMessage)].content);return;}
      const editButton=e.target.closest('[data-edit-message]');if(editButton){if(waitForImages())return;if(activeRequest){toast('请先停止当前生成。');return;}const index=Number(editButton.dataset.editMessage),message=activeChat.messages[index];$('messageInput').value=message.content;attachments=message.attachments?.slice() || [];activeChat.messages=activeChat.messages.slice(0,index);persist();renderMessages();renderAttachments();$('messageInput').focus();return;}
      const regenerateButton=e.target.closest('[data-regenerate]');if(regenerateButton){regenerate(regenerateButton.dataset.regenerate);return;}
      const readButton=e.target.closest('[data-read-message]');if(readButton){const text=activeChat.messages[Number(readButton.dataset.readMessage)].content;if(native?.speak)native.speak(text);else if(window.speechSynthesis){speechSynthesis.cancel();speechSynthesis.speak(new SpeechSynthesisUtterance(text));}else toast('此手机暂不支持朗读。');return;}
      const mediaButton=e.target.closest('[data-open-media]');if(mediaButton){if(native?.saveMedia)native.saveMedia(mediaButton.dataset.openMedia);else openExternal(mediaButton.dataset.openMedia);}
    };
    $('messages').addEventListener('error',mediaPlaybackEvent,true);
    $('messages').addEventListener('loadedmetadata',mediaPlaybackEvent,true);
    document.addEventListener('click',e=>{const link=e.target.closest('a[href]');if(link){const href=link.getAttribute('href');if(/^https?:\/\//i.test(href)){e.preventDefault();openExternal(href);}else if(!href.startsWith('#'))e.preventDefault();}const suggestion=e.target.closest('[data-prompt]');if(suggestion){$('messageInput').value=suggestion.dataset.prompt;resizeComposer();$('messageInput').focus();}const action=e.target.closest('[data-action="generate-image"]');if(action)openImages();if(e.target.closest('[data-action="image"],[data-action="attach-image"]'))pickImages();});
    $('messageViewport').onscroll=()=>{const el=$('messageViewport');forceScroll=el.scrollHeight-el.scrollTop-el.clientHeight<120;$('scrollBottomButton').hidden=forceScroll || !activeChat;};
    $('scrollBottomButton').onclick=()=>{forceScroll=true;$('messageViewport').scrollTo({top:$('messageViewport').scrollHeight,behavior:'smooth'});};
    $('exportButton').onclick=exportChats;$('importButton').onclick=()=>native?.importChats?native.importChats():$('importInput').click();
    $('importInput').onchange=async()=>{if($('importInput').files[0])importChats(await $('importInput').files[0].text());$('importInput').value='';};
    $('clearHistoryButton').onclick=async()=>{if(waitForImages())return;if(await confirmAction('清空所有对话？','这个操作会删除此手机上的全部历史对话。综合记忆可在记忆设置中单独清空。')){stopGeneration();chats=[];persist();newChat();toast('历史对话已清空');}};
    matchMedia('(prefers-color-scheme: dark)').addEventListener('change',applyTheme);
    document.addEventListener('visibilitychange',()=>{if(document.hidden)persist();});
    if(window.visualViewport){const adjust=()=>{document.documentElement.style.setProperty('--viewport-height',`${visualViewport.height}px`);};visualViewport.addEventListener('resize',adjust);adjust();}
  }
  async function init() {
    try { if(native?.loadChats)chats=JSON.parse(native.loadChats() || '[]'); }catch{/* use legacy cache */}
    let migratedIds = false;
    for (const chat of chats) if (ensureMessageIds(chat)) migratedIds = true;
    bind();getConfig();loadSharedMemory();applyTheme();$('systemPromptInput').value=prefs.systemPrompt;
    try { const results=await Promise.allSettled([fetch('model-catalog.json').then(r=>r.json()),fetch('gateway-endpoints.json').then(r=>r.json())]);if(!models.length&&results[0].status==='fulfilled')models=results[0].value.data || results[0].value;if(results[1].status==='fulfilled')endpointMap=results[1].value; }catch{/* use last cache */}
    // Interrupted generations remain readable after Android reclaims the app process.
    for(const chat of chats)for(const message of chat.messages)if(message.streaming){message.streaming=false;message.incomplete=true;if(message.task){message.task.status='paused';message.content='任务编号已保存，可以继续查询视频。';}else if(!message.content)message.content='生成已中断，请重新生成。';}
    try{if(native?.loadInterfaces)applyInterfaces(JSON.parse(native.loadInterfaces()));}catch{/* no synchronized metadata yet */}
    ensureModel();if(migratedIds)persist();else syncSharedMemory();updateMemoryStatus();updateModelLabel();renderHistory();renderMessages();renderModels();resizeComposer();updateStorageInfo();
    try {
      const picked = JSON.parse(native?.takePickedImages?.() || 'null');
      const duplicate = typeof picked?.pickerEventId === 'string' && pickedImageEvents.has(picked.pickerEventId);
      if (picked?.id === 'imagePicker' && ['data','error','done'].includes(picked.type) && !duplicate) {
        pickingImages = true; imagePickerChat = activeChat; window.__nativeEvent(picked);
      }
    } catch { /* no pending image selection or older bridge */ }
    if(config.hasKey)refreshModels(true);else setTimeout(()=>showSheet('settingsSheet'),350);
  }
  init();
})();
