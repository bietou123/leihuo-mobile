/* Standard task converters: New API v1.0.0-rc.21. See docs/video-routing.md. */
(function () {
  'use strict';

  const DEFAULTS = Object.freeze({duration: 8, aspectRatio: '16:9', resolution: '720p', generateAudio: true});
  const TASK = Object.freeze({
    path: '/v1/video/generations/$TASK_ID', method: 'GET',
    idPaths: ['task_id', 'id', 'data.task_id', 'data.id'],
    statusPaths: ['data.status', 'status', 'data.task_status', 'task_status'],
    mediaPaths: ['data.url', 'data.result_url', 'url', 'result_url', 'metadata.url', 'data.data.content.video_url', 'data.data.output.video_url'],
    errorPaths: ['data.fail_reason', 'data.reason', 'data.error.message', 'data.data.error.message', 'data.data.output.message', 'error.message', 'message'],
    contentPath: '/v1/videos/$TASK_ID/content'
  });
  const WAN_SIZES = Object.freeze({
    '480p': {'16:9': '832*480', '9:16': '480*832', '1:1': '624*624'},
    '720p': {'16:9': '1280*720', '9:16': '720*1280', '1:1': '960*960'},
    '1080p': {'16:9': '1920*1080', '9:16': '1080*1920', '1:1': '1440*1440'}
  });

  function modelId(model) {
    return typeof model === 'string' ? model : typeof model?.id === 'string' ? model.id : '';
  }

  // These are converter families, not a list used to hide catalog models.
  function profileFor(model) {
    const id = modelId(model).toLowerCase();
    if (id === 'minimax-h3' || id === 'minimax-h3-max') {
      const isMax = id === 'minimax-h3-max';
      const minDuration = isMax ? 5 : 4;
      return {
        kind: 'minimax-h3', label: isMax ? 'MiniMax H3 Max 视频' : 'MiniMax H3 视频',
        defaults: {...DEFAULTS, duration: 10, resolution: '768p'},
        durations: Array.from({length: 16 - minDuration}, (_, index) => index + minDuration),
        minDuration, maxDuration: 15,
        resolutions: isMax ? ['480p', '768p'] : ['768p', '2k'],
        ratios: ['21:9', '16:9', '4:3', '1:1', '3:4', '9:16'],
        maxImages: 1, imageRatioFromSource: true, requiresSyncedEndpoint: true,
        maxTextCharacters: 7000, audioAlwaysGenerated: true
      };
    }
    if (/^veo-/.test(id)) return {kind: 'veo', label: 'Veo 视频', defaults: {...DEFAULTS}, durations: [4, 6, 8], minDuration: 4, maxDuration: 8, resolutions: ['720p', '1080p'], ratios: ['16:9', '9:16'], maxImages: 1};
    if (/^doubao-seedance-/.test(id)) {
      const resolutions = /^doubao-seedance-2-0-(?:fast|mini)-/.test(id) ? ['480p', '720p'] : /^doubao-seedance-2-0-\d/.test(id) ? ['480p', '720p', '1080p', '4k'] : ['480p', '720p', '1080p'];
      const maxDuration = /^doubao-seedance-1-/.test(id) ? 12 : /^doubao-seedance-2-0-/.test(id) ? 15 : 30;
      return {kind: 'seedance', label: 'Seedance 视频', defaults: {...DEFAULTS, duration: 5}, minDuration: 4, maxDuration, resolutions, ratios: ['16:9', '9:16', '1:1'], maxImages: null};
    }
    if (/^wan\d/.test(id) && /(?:video|t2v|i2v)/.test(id)) {
      const modern = /^wan3\.0-video/.test(id);
      const durations = /^wan2\.5-/.test(id) ? [5, 10] : /^wan2\.2-/.test(id) ? [5] : /-us$/.test(id) ? [5, 10, 15] : null;
      return {kind: 'wan', label: 'Wan 视频', defaults: {...DEFAULTS, duration: 5}, durations, minDuration: 2, maxDuration: modern ? 30 : /^wan2\.[67]-/.test(id) ? 15 : 10, resolutions: ['480p', '720p', '1080p'], ratios: ['16:9', '9:16', '1:1'], maxImages: 1, modern, imageRatioFromSource: !modern};
    }
    return null;
  }

  function defaultsFor(model) {
    return {...(profileFor(model)?.defaults || DEFAULTS)};
  }

  function constraintsFor(model) {
    const profile = profileFor(model);
    if (!profile) return null;
    return {
      durations: profile.durations || [4, 5, 6, 8, 10, 15, 30].filter(value => value >= profile.minDuration && value <= profile.maxDuration),
      minDuration: profile.minDuration, maxDuration: profile.maxDuration,
      resolutions: [...profile.resolutions], ratios: [...profile.ratios], maxImages: profile.maxImages
    };
  }

  function optionsFor(model) {
    const profile = profileFor(model);
    if (!profile) return [];
    const fields = [
      profile.durations
        ? {key: 'duration', label: '时长', type: 'select', default: profile.defaults.duration, choices: profile.durations.map(value => ({value, label: `${value} 秒`}))}
        : {key: 'duration', label: '时长（秒）', type: 'number', default: profile.defaults.duration, min: profile.minDuration, max: profile.maxDuration, step: 1},
      {key: 'aspectRatio', label: '画面比例', type: 'select', default: profile.defaults.aspectRatio, choices: profile.ratios.map(value => ({value, label: value}))},
      {key: 'resolution', label: '分辨率', type: 'select', default: profile.defaults.resolution, choices: profile.resolutions.map(value => ({value, label: value}))},
      {key: 'generateAudio', label: '生成声音', type: 'checkbox', default: true}
    ];
    return profile.audioAlwaysGenerated ? fields.filter(field => field.key !== 'generateAudio') : fields;
  }

  function isVideoModel(model) {
    return !!profileFor(model) || /^minimax-h/i.test(modelId(model)) || (model?.supported_endpoint_types || []).some(type => /video|seedance|h3_|^create$/.test(type));
  }

  function choice(options, key, fallback, allowed, label) {
    const raw = options[key];
    const value = raw === undefined || raw === null || raw === '' ? fallback : String(raw).trim().toLowerCase();
    if (!allowed.includes(value)) throw new Error(`请选择该模型支持的${label}：${allowed.join('、')}。`);
    return value;
  }

  function imageInputs(files, profile) {
    const list = Array.isArray(files) ? files : files ? [files] : [];
    const images = [];
    for (const file of list) {
      if (!file || !(file.type === 'image' || /^image\//i.test(file.mime || '') || /^data:image\//i.test(file.data || ''))) {
        throw new Error('这个视频生成入口仅支持文字和图片附件，请移除其他类型的文件。');
      }
      const data = typeof file.data === 'string' ? file.data : '';
      const url = typeof file.url === 'string' ? file.url : '';
      if (/^data:image\/(?:png|jpe?g|webp);base64,[A-Za-z0-9+/=\r\n]+$/i.test(data)) images.push(data);
      else if (/^https:\/\//i.test(url)) images.push(url);
      else throw new Error('图片尚未读取完成或格式不支持，请重新添加 PNG、JPEG 或 WebP 图片。');
    }
    if (profile.maxImages !== null && images.length > profile.maxImages) {
      throw new Error('该视频入口一次使用一张图片，请只保留一张参考图片。');
    }
    return images;
  }

  function validLocalPath(value) {
    if (typeof value !== 'string' || !/^\/(?!\/)/.test(value) || /[\s\\\u0000-\u001f#]/.test(value) || /%(?![0-9a-f]{2})/i.test(value)) return false;
    try {
      const decoded = decodeURIComponent(value.split('?')[0]);
      if (decoded.startsWith('//') || /[\\\u0000-\u001f]/.test(decoded) || decoded.split('/').some(part => part === '.' || part === '..')) return false;
      const parsed = new URL(value, 'https://leihuo.invalid');
      return parsed.origin === 'https://leihuo.invalid' && parsed.pathname !== '/';
    } catch { return false; }
  }

  // A native Seedance submit path alone does not prove its query path. Only
  // use native requests when both routes were explicitly authenticated.
  function nativeSeedanceRoute(endpointMap) {
    const route = endpointMap?.seedance;
    const task = route?.task;
    if (route?.synced !== true || String(route.method || '').toUpperCase() !== 'POST' || !validLocalPath(route.path)) return null;
    if (task?.synced !== true || String(task.method || '').toUpperCase() !== 'GET' || !validLocalPath(task.path) || !task.path.includes('$TASK_ID')) return null;
    return {endpoint: route.path, method: 'POST', task: {...TASK, ...task}};
  }

  function syncedRoute(endpointMap, type, method) {
    const route = endpointMap?.[type];
    if (route?.synced !== true || String(route.method || '').toUpperCase() !== method || !validLocalPath(route.path)) return null;
    return route;
  }

  function nativeH3Route(endpointMap) {
    const create = syncedRoute(endpointMap, 'create', 'POST');
    const query = syncedRoute(endpointMap, 'query_by_task_id', 'GET');
    if (!create || !query) return null;
    let queryPath = query.path.replace(/\{(?:task_id|id)\}|:(?:task_id|id)(?=\/|\?|$)/g, () => '$TASK_ID');
    // The official V2 single-task query appends the ID to this exact path;
    // keep the gateway prefix from authenticated metadata.
    if (!queryPath.includes('$TASK_ID')) {
      if (!queryPath.endsWith('/query/video_generation')) return null;
      queryPath += '/$TASK_ID';
    }
    if (!validLocalPath(queryPath)) return null;
    return {
      endpoint: create.path, method: 'POST',
      task: {
        path: queryPath, method: 'GET',
        idPaths: ['task_id', 'data.task_id', 'task.id', 'data.task.id'],
        statusPaths: ['task.status', 'data.task.status', 'data.status', 'status'],
        mediaPaths: ['task.content.url', 'data.task.content.url'],
        errorPaths: ['task.error.message', 'data.task.error.message', 'error.message', 'task.message', 'data.task.message', 'message']
      }
    };
  }

  function template(model, prompt, files, options = {}, endpointMap = {}) {
    const profile = profileFor(model);
    if (!profile) return null;
    const h3Route = profile.kind === 'minimax-h3' ? nativeH3Route(endpointMap) : null;
    if (profile.kind === 'minimax-h3' && !h3Route) return null;
    options = options && typeof options === 'object' ? options : {};
    const text = prompt === undefined || prompt === null ? '' : String(prompt);
    if (!text.trim()) throw new Error('请填写希望生成的视频内容。');
    if (profile.maxTextCharacters && Array.from(text).length > profile.maxTextCharacters) throw new Error('MiniMax H3 提示词不能超过 7,000 字符，请缩短后再发送。');
    const rawDuration = options.duration;
    const duration = rawDuration === undefined || rawDuration === null || rawDuration === '' ? profile.defaults.duration : Number(rawDuration);
    if (!Number.isSafeInteger(duration) || duration < profile.minDuration || duration > profile.maxDuration || (profile.durations && !profile.durations.includes(duration))) {
      const range = profile.durations ? profile.durations.join('、') : `${profile.minDuration} 到 ${profile.maxDuration}`;
      throw new Error(`${profile.label}时长请选择 ${range} 秒。`);
    }
    const aspectRatio = choice(options, 'aspectRatio', profile.defaults.aspectRatio, profile.ratios, '画面比例');
    const resolution = choice(options, 'resolution', profile.defaults.resolution, profile.resolutions, '分辨率');
    const rawAudio = options.generateAudio;
    if (rawAudio !== undefined && rawAudio !== null && ![true, false, 'true', 'false'].includes(rawAudio)) throw new Error('生成声音选项无效，请重新选择。');
    const generateAudio = rawAudio === undefined || rawAudio === null ? true : rawAudio === true || rawAudio === 'true';
    const images = imageInputs(files, profile);
    if (h3Route) {
      for (const url of images) {
        if (!url.startsWith('data:')) continue;
        const encoded = url.slice(url.indexOf(',') + 1).replace(/[\r\n]/g, '');
        const padding = encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0;
        if (encoded.length * 3 / 4 - padding > 30 * 1024 * 1024) throw new Error('MiniMax H3 首帧图片不能超过 30 MB，请缩小图片后再添加。');
      }
      return {...h3Route, body: {
        model: modelId(model),
        content: [{type: 'text', text}, ...images.map(url => ({type: 'image_url', image_url: {url}, role: 'first_frame'}))],
        resolution: resolution.toUpperCase(), duration,
        ratio: images.length ? 'adaptive' : aspectRatio
      }};
    }
    const body = {model: modelId(model), prompt: text, duration};
    if (images.length) body.images = images;

    if (profile.kind === 'veo') {
      body.metadata = {aspectRatio, resolution, generateAudio};
    } else if (profile.kind === 'seedance') {
      body.seconds = String(duration); // rc.21 Doubao does not read top-level duration.
      body.metadata = {resolution, ratio: aspectRatio, generate_audio: generateAudio};
      const nativeRoute = nativeSeedanceRoute(endpointMap);
      if (nativeRoute) {
        return {...nativeRoute, body: {
          model: body.model,
          content: [{type: 'text', text}, ...images.map(url => ({type: 'image_url', image_url: {url}}))],
          duration, resolution, ratio: aspectRatio, generate_audio: generateAudio
        }};
      }
    } else if (profile.modern) {
      // Wan 3 uses resolution + ratio, with images inside input.media. The
      // explicit empty legacy size clears rc.21's initialized size field.
      body.size = resolution;
      body.metadata = {
        input: {prompt: text, img_url: '', ...(images.length ? {media: [{type: 'first_frame', url: images[0]}]} : {})},
        parameters: {size: '', resolution: resolution.toUpperCase(), ratio: aspectRatio, duration, audio: generateAudio}
      };
    } else {
      // Ali merges metadata into the whole upstream request, so these fields
      // must be nested under parameters. Images use the source image's ratio.
      body.size = images.length ? resolution.toUpperCase() : WAN_SIZES[resolution][aspectRatio];
      body.metadata = {parameters: {duration, audio: generateAudio}};
    }
    return {endpoint: '/v1/video/generations', method: 'POST', body, task: {...TASK}};
  }

  window.LeihuoVideo = Object.freeze({template, profileFor, defaultsFor, constraintsFor, optionsFor, isVideoModel, defaults: DEFAULTS});
}());
