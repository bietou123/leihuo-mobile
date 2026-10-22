/* Audio request formats and their sources are documented in docs/audio-routing.md. */
(function () {
  'use strict';

  const MAX_ENCODED_BYTES = 10000000;
  const DEFAULT_MINIMAX_VOICE = 'Chinese (Mandarin)_Lyrical_Voice';
  const DEFAULT_SEED_VOICE = 'zh_female_vv_uranus_bigtts';
  const seedVoices = [
    {value: DEFAULT_SEED_VOICE, label: 'Vivi 2.0'},
    {value: 'zh_female_xiaohe_uranus_bigtts', label: '小何 2.0'},
    {value: 'zh_male_m191_uranus_bigtts', label: '云舟 2.0'},
    {value: 'zh_male_taocheng_uranus_bigtts', label: '小天 2.0'}
  ];
  const mimoVoices = ['mimo_default', '冰糖', '茉莉', '苏打', '白桦', 'Mia', 'Chloe', 'Milo', 'Dean'];
  const styleField = {key: 'style', label: '朗读风格', type: 'textarea', default: '', placeholder: '例如：自然、温柔，语速稍慢'};
  const definitions = {
    mimoAsr: {
      kind: 'asr', label: '语音识别', requiresAudio: true, sampleLabel: '识别音频',
      defaults: {language: 'auto'},
      fields: [{key: 'language', label: '音频语言', type: 'select', default: 'auto', choices: [
        {value: 'auto', label: '自动识别'}, {value: 'zh', label: '中文'}, {value: 'en', label: '英文'}
      ]}]
    },
    mimoTts: {
      kind: 'tts', label: '语音合成', requiresAudio: false,
      defaults: {voice: 'mimo_default', style: ''},
      fields: [
        {key: 'voice', label: '声音', type: 'select', default: 'mimo_default', choices: mimoVoices.map(value => ({value, label: value === 'mimo_default' ? '默认声音' : value}))},
        styleField
      ]
    },
    mimoClone: {
      kind: 'voiceclone', label: '声音克隆', requiresAudio: true, sampleLabel: '声音样本',
      defaults: {style: ''}, fields: [styleField]
    },
    mimoDesign: {
      kind: 'voicedesign', label: '声音设计', requiresAudio: false,
      defaults: {style: '', optimizeTextPreview: false},
      fields: [
        {key: 'style', label: '声音描述', type: 'textarea', default: '', required: true, placeholder: '例如：年轻女性，普通话，声音温柔明亮，语速适中'},
        {key: 'optimizeTextPreview', label: '优化朗读文字', type: 'checkbox', default: false}
      ]
    },
    seedAudio: {
      kind: 'seed-audio', label: '音频生成', requiresAudio: false,
      defaults: {}, fields: [], maxTextCharacters: 3000, requiresSyncedEndpoint: true
    },
    seedTts: {
      kind: 'seed-tts', label: '语音合成', requiresAudio: false,
      defaults: {voice: DEFAULT_SEED_VOICE}, requiresSyncedEndpoint: true,
      fields: [{key: 'voice', label: '声音', type: 'select', default: DEFAULT_SEED_VOICE, choices: seedVoices}]
    },
    minimaxSpeech: {
      kind: 'minimax-tts', label: '语音合成', requiresAudio: false,
      defaults: {voice: DEFAULT_MINIMAX_VOICE, speed: 1, volume: 1, pitch: 0},
      fields: [
        {key: 'voice', label: '音色 ID', type: 'text', default: DEFAULT_MINIMAX_VOICE, placeholder: '填写 MiniMax 音色 ID'},
        {key: 'speed', label: '语速', type: 'number', default: 1, min: 0.5, max: 2, step: 0.1},
        {key: 'volume', label: '音量', type: 'number', default: 1, min: 0.1, max: 10, step: 0.1},
        {key: 'pitch', label: '语调', type: 'number', default: 0, min: -12, max: 12, step: 1}
      ]
    }
  };

  function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) {
      Object.values(value).forEach(freeze);
      Object.freeze(value);
    }
    return value;
  }
  freeze(definitions);

  function modelId(model) {
    return typeof model === 'string' ? model : typeof model?.id === 'string' ? model.id : '';
  }

  function profileKey(model) {
    const id = modelId(model).toLowerCase();
    if (/^mimo-.+-tts-voiceclone$/.test(id)) return 'mimoClone';
    if (/^mimo-.+-tts-voicedesign$/.test(id)) return 'mimoDesign';
    if (/^mimo-.+-asr$/.test(id)) return 'mimoAsr';
    if (/^mimo-.+-tts$/.test(id)) return 'mimoTts';
    if (/^seed-audio-1\.0(?:$|-)/.test(id)) return 'seedAudio';
    if (/^seed-tts-2\.0(?:$|-)/.test(id)) return 'seedTts';
    if (/^speech-(?:\d+(?:\.\d+)?)-(?:hd|turbo)(?:-preview)?$/.test(id)) return 'minimaxSpeech';
    return '';
  }

  function profileFor(model) {
    return definitions[profileKey(model)] || null;
  }

  function optionsFor(model) {
    const fields = profileFor(model)?.fields || [];
    return fields.map(field => ({...field, ...(field.choices ? {choices: field.choices.map(choice => ({...choice}))} : {})}));
  }

  function isInputModel(model) {
    return !!profileFor(model)?.requiresAudio;
  }

  function isAudioModel(model) {
    if (profileFor(model)) return true;
    if (/^seed-(?:tts|audio)-/i.test(modelId(model))) return true;
    return (model?.supported_endpoint_types || []).some(type => /^(?:voice-generation|minimax-t2a_v2|openspeech_create|openspeech_unidirectional|openai-audio-(?:speech|transcription|translation))$/.test(type));
  }

  function textOption(options, key, fallback = '') {
    const value = options[key];
    return value === undefined || value === null ? fallback : String(value).trim();
  }

  function numberOption(options, key, fallback, min, max, label, integer = false, exclusiveMin = false) {
    const raw = options[key];
    const value = raw === undefined || raw === null || String(raw).trim() === '' ? fallback : Number(raw);
    if (!Number.isFinite(value) || value > max || (exclusiveMin ? value <= min : value < min) || (integer && !Number.isInteger(value))) {
      const range = exclusiveMin ? `大于 ${min} 且不超过 ${max}` : `${min} 到 ${max}`;
      throw new Error(`${label}需${integer ? '为整数，且' : ''}在有效范围内：${range}。`);
    }
    return value;
  }

  function requiredText(prompt) {
    const text = prompt === undefined || prompt === null ? '' : String(prompt);
    if (!text.trim()) throw new Error('请填写需要朗读的文字。');
    return text;
  }

  function audioSample(files, profile) {
    const list = Array.isArray(files) ? files : files ? [files] : [];
    const file = list.find(item => item && (item.type === 'audio' || /^audio\//i.test(item.mime || '') || /^data:audio\//i.test(item.data || '')));
    const label = profile.sampleLabel || '音频';
    if (!file) throw new Error(`请先添加${label}，支持 WAV 或 MP3。`);
    if (typeof file.data !== 'string' || !file.data) throw new Error(`${label}尚未读取完成，请重新添加文件。`);
    if (file.data.length > MAX_ENCODED_BYTES) throw new Error(`${label}编码后不能超过 10 MB，请使用更短的 WAV 或 MP3 文件。`);
    const match = /^data:([^;,]+);base64,([A-Za-z0-9+/=\r\n]+)$/i.exec(file.data);
    if (!match) throw new Error(`${label}的数据无效，请重新添加 WAV 或 MP3 文件。`);
    const mime = match[1].toLowerCase();
    const format = /^(?:audio\/(?:wav|x-wav|wave|vnd\.wave))$/.test(mime) ? 'wav' : /^(?:audio\/(?:mpeg|mp3|x-mp3))$/.test(mime) ? 'mp3' : '';
    if (!format) throw new Error(`${label}仅支持 WAV 或 MP3；请将 M4A、AAC 等格式转换后再添加。`);
    const declaredFormat = typeof file.format === 'string' ? file.format.toLowerCase().replace(/^\./, '') : '';
    if (declaredFormat && declaredFormat !== format && !(format === 'mp3' && declaredFormat === 'mpeg')) {
      throw new Error(`${label}的格式标记与文件内容不一致，请重新添加 WAV 或 MP3 文件。`);
    }
    const base64 = match[2].replace(/[\r\n]/g, '');
    if (!base64 || base64.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) {
      throw new Error(`${label}的编码无效，请重新添加文件。`);
    }
    const data = `data:${format === 'wav' ? 'audio/wav' : 'audio/mpeg'};base64,${base64}`;
    if (data.length > MAX_ENCODED_BYTES) throw new Error(`${label}编码后不能超过 10 MB，请使用更短的 WAV 或 MP3 文件。`);
    let header;
    try { header = atob(base64.slice(0, 64)); } catch { throw new Error(`${label}的编码无效，请重新添加文件。`); }
    const wav = header.slice(0, 4) === 'RIFF' && header.slice(8, 12) === 'WAVE';
    const mp3 = header.slice(0, 3) === 'ID3' || (header.charCodeAt(0) === 255 && (header.charCodeAt(1) & 224) === 224);
    if (format === 'wav' ? !wav : !mp3) throw new Error(`${label}的内容与 WAV 或 MP3 格式不匹配，请重新选择音频文件。`);
    return {data, format};
  }

  function chatTemplate(body) {
    return {endpoint: '/v1/chat/completions', method: 'POST', body};
  }

  function syncedRoute(endpointMap, type) {
    const route = endpointMap && typeof endpointMap === 'object' ? endpointMap[type] : null;
    if (!route || route.synced !== true || String(route.method || '').toUpperCase() !== 'POST') return null;
    const path = typeof route.path === 'string' ? route.path : '';
    if (!/^\/(?!\/)/.test(path) || /[\s\\\u0000-\u001f#]/.test(path) || /%(?![0-9a-f]{2})/i.test(path)) return null;
    try {
      const decoded = decodeURIComponent(path.split('?')[0]);
      if (decoded.startsWith('//') || /[\\\u0000-\u001f]/.test(decoded) || decoded.split('/').some(part => part === '.' || part === '..')) return null;
      const url = new URL(path, 'https://leihuo.invalid');
      if (url.origin !== 'https://leihuo.invalid' || url.pathname === '/') return null;
    } catch { return null; }
    return {endpoint: path, method: 'POST'};
  }

  // Seed native paths must come from authenticated gateway metadata. The
  // provider's public path is not substituted for an unknown gateway path.
  function template(model, prompt, files, options = {}, endpointMap = {}) {
    const profile = profileFor(model);
    if (!profile) return null;
    options = options && typeof options === 'object' ? options : {};
    const id = modelId(model);
    if (profile.kind === 'seed-audio') {
      const route = syncedRoute(endpointMap, 'openspeech_create');
      if (!route) return null;
      const text = prompt === undefined || prompt === null ? '' : String(prompt);
      if (!text.trim()) throw new Error('请填写希望生成的音频描述或文字。');
      if (Array.from(text).length > 3000) throw new Error('Seed Audio 提示词不能超过 3,000 字符，请缩短后再发送。');
      return {...route, body: {model: id, text_prompt: text, audio_config: {format: 'mp3', sample_rate: 44100}}, audioMime: 'audio/mpeg'};
    }
    if (profile.kind === 'seed-tts') {
      const route = syncedRoute(endpointMap, 'openspeech_unidirectional');
      if (!route) return null;
      const voice = textOption(options, 'voice', DEFAULT_SEED_VOICE) || DEFAULT_SEED_VOICE;
      if (!seedVoices.some(item => item.value === voice)) throw new Error('请选择 Seed TTS 2.0 支持的声音：Vivi、小何、云舟或小天。');
      return {
        ...route, headers: {'X-Api-Resource-Id': id},
        body: {user: {uid: 'leihuo-mobile'}, req_params: {text: requiredText(prompt), speaker: voice, audio_params: {format: 'mp3', sample_rate: 24000}}},
        audioMime: 'audio/mpeg', audioChunkStream: true
      };
    }
    if (profile.kind === 'asr') {
      const sample = audioSample(files, profile);
      const language = textOption(options, 'language', 'auto') || 'auto';
      if (!['auto', 'zh', 'en'].includes(language)) throw new Error('音频语言请选择自动识别、中文或英文。');
      return chatTemplate({
        model: id,
        messages: [{role: 'user', content: [{type: 'input_audio', input_audio: {data: sample.data}}]}],
        asr_options: {language},
        stream: false
      });
    }
    if (profile.kind === 'minimax-tts') {
      const text = requiredText(prompt);
      if (Array.from(text).length >= 10000) throw new Error('MiniMax 朗读文字需少于 10,000 字符，请缩短后再发送。');
      const voice = textOption(options, 'voice', DEFAULT_MINIMAX_VOICE) || DEFAULT_MINIMAX_VOICE;
      const speed = numberOption(options, 'speed', 1, 0.5, 2, '语速');
      const volume = numberOption(options, 'volume', 1, 0, 10, '音量', false, true);
      const pitch = numberOption(options, 'pitch', 0, -12, 12, '语调', true);
      return {
        endpoint: '/v1/audio/speech', method: 'POST',
        body: {
          model: id, input: text, voice, speed, response_format: 'hex',
          metadata: {
            voice_setting: {voice_id: voice, speed, vol: volume, pitch},
            audio_setting: {format: 'mp3', sample_rate: 32000, bitrate: 128000, channel: 1},
            output_format: 'hex'
          }
        }
      };
    }

    const style = textOption(options, 'style');
    const audio = {format: 'wav'};
    const messages = [];
    if (profile.kind === 'voicedesign') {
      if (!style) throw new Error('请填写声音描述，例如：年轻女性，普通话，声音温柔明亮。');
      messages.push({role: 'user', content: style});
      const optimize = options.optimizeTextPreview === true || options.optimizeTextPreview === 'true';
      if (optimize) audio.optimize_text_preview = true;
      if (prompt !== undefined && prompt !== null && String(prompt).trim()) messages.push({role: 'assistant', content: String(prompt)});
      else if (!optimize) throw new Error('请填写需要朗读的文字，或开启优化朗读文字。');
    } else {
      if (profile.kind === 'voiceclone') audio.voice = audioSample(files, profile).data;
      else {
        const voice = textOption(options, 'voice', 'mimo_default') || 'mimo_default';
        if (!mimoVoices.includes(voice)) throw new Error('请选择 MiMo 支持的内置声音。');
        audio.voice = voice;
      }
      if (style) messages.push({role: 'user', content: style});
      messages.push({role: 'assistant', content: requiredText(prompt)});
    }
    return chatTemplate({model: id, messages, audio, stream: false});
  }

  window.LeihuoAudio = Object.freeze({
    template, profileFor, optionsFor, isInputModel, isAudioModel,
    profiles: definitions, MAX_ENCODED_BYTES, DEFAULT_MINIMAX_VOICE, DEFAULT_SEED_VOICE
  });
}());
