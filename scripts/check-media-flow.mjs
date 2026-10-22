// Offline UI integration check. Native responses are fixtures; no model API is called.
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {JSDOM}=require(path.join(process.env.LOCALAPPDATA,'leihuo-web-check/node_modules/jsdom/lib/api.js'));
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const assets=path.join(root,'app/src/main/assets');
const html=(await fs.readFile(path.join(assets,'index.html'),'utf8')).replace(/<script\b[^>]*>[\s\S]*?<\/script>/g,'');
const catalog=JSON.parse(await fs.readFile(path.join(assets,'model-catalog.json'),'utf8'));
const routes=JSON.parse(await fs.readFile(path.join(assets,'gateway-endpoints.json'),'utf8'));
const dom=new JSDOM(html,{url:'https://leihuo.local/index.html',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,$=id=>w.document.getElementById(id);
let saved=[],synced={},videoCount=0,finishPaused=false,lastTask='',calls=[];
w.matchMedia=()=>({matches:false,addEventListener(){}});
w.HTMLElement.prototype.scrollTo=function({top}){this.scrollTop=top;};
w.HTMLDialogElement.prototype.showModal=function(){this.setAttribute('open','');};
w.HTMLDialogElement.prototype.close=function(){this.removeAttribute('open');};
const timer=w.setTimeout.bind(w);w.setTimeout=(fn,ms,...args)=>timer(fn,ms===5000?5:ms,...args);
w.fetch=async name=>({json:async()=>String(name).includes('model-catalog')?catalog:routes});
const localMedia=(mime)=>({__localMedia:true,mime,url:`https://leihuo.local/generated-media/00000000-0000-0000-0000-000000000000.${mime.includes('video')?'mp4':'wav'}`});
function response(options){
  const body=options.body?JSON.parse(options.body):null;
  if(options.endpoint==='/v1/models')return catalog;
  if(options.endpoint==='/v1/images/generations'){assert.equal(body.model,'gpt-image-2');assert.equal(body.prompt,'生成一只橘猫');return{data:[{b64_json:'AQ=='}]};}
  if(options.endpoint==='/v1/audio/speech'){assert.equal(body.model,'speech-2.8-hd');assert.equal(body.input,'欢迎使用雷火');assert.equal(body.response_format,'hex');assert.equal(body.metadata.audio_setting.format,'mp3');assert.equal(options.responseAsMedia,true);return localMedia('audio/mpeg');}
  if(options.endpoint==='/v1/chat/completions'){
    if(body.model==='mimo-v2.5-asr'){assert.match(body.messages[0].content[0].input_audio.data,/^data:audio\/wav;base64,/);assert.equal(body.asr_options.language,'auto');return{choices:[{message:{content:'这是转写结果。'}}]};}
    assert.equal(body.model,'mimo-v2.5-tts');assert.equal(body.messages.at(-1).role,'assistant');assert.equal(body.messages.at(-1).content,'你好，测试声音');assert.equal(body.audio.voice,'mimo_default');return{...localMedia('audio/wav'),choices:[{message:{content:''}}]};
  }
  if(options.endpoint==='/fixture/seed-tts'){
    assert.equal(options.audioChunkStream,true);assert.equal(options.audioMime,'audio/mpeg');assert.equal(body.req_params.text,'同步后的语音');assert.equal(options.headers['X-Api-Resource-Id'],'seed-tts-2.0');return localMedia('audio/mpeg');
  }
  if(options.endpoint==='/v1/video/generations'){
    assert.equal(body.model,'veo-3.1-fast-generate-preview');assert.equal(body.duration,8);assert.equal(body.metadata.aspectRatio,'16:9');
    videoCount++;lastTask=`task-${videoCount}`;return{id:lastTask,status:'queued'};
  }
  if(options.endpoint==='/v1/video/generations/task-1')return{code:'success',data:{task_id:'task-1',status:'SUCCESS',url:'https://fixtures.invalid/video-without-extension'}};
  if(options.endpoint==='/v1/video/generations/task-2')return{code:'success',data:{task_id:'task-2',status:finishPaused?'SUCCESS':'IN_PROGRESS',...(finishPaused?{url:'https://fixtures.invalid/resumed-video'}:{progress:25})}};
  if(options.endpoint==='/v1/video/generations/task-3')return{code:'success',data:{task_id:'task-3',status:'SUCCESS'}};
  if(options.endpoint==='/v1/videos/task-3/content'){assert.equal(options.responseAsMedia,true);return localMedia('video/mp4');}
  throw new Error(`Unexpected fixture request: ${options.endpoint}`);
}
w.Native={
  getConfig:()=>JSON.stringify({baseUrl:'https://ai.leihuo.netease.com',hasKey:true}),
  loadChats:()=>JSON.stringify(saved),saveChats:json=>{saved=JSON.parse(json);return'{"ok":true}';},
  setTheme(){},copy(){},cancel(){},loadInterfaces:()=>JSON.stringify(synced),
  syncInterfaces(){synced={endpoints:{openspeech_unidirectional:{path:'/fixture/seed-tts',method:'POST'}},syncedAt:Date.now()};w.__nativeEvent({id:'interfaces',type:'data',data:JSON.stringify(synced)});},
  requestWithOptions(id,json){
    const options=JSON.parse(json);calls.push(options);
    timer(()=>{try{const result=response(options);w.__nativeEvent({id,type:'data',data:JSON.stringify(result)});w.__nativeEvent({id,type:'done'});}catch(error){w.__nativeEvent({id,type:'error',data:error.message,status:500});}},1);
  }
};
for(const file of ['audio-profiles.js','video-profiles.js','app.js'])w.eval(await fs.readFile(path.join(assets,file),'utf8'));
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(test,label){for(let i=0;i<200;i++){if(test())return;await pause(5);}throw new Error(`Timeout: ${label}`);}
await until(()=>$('modelList').children.length>0,'catalog loaded');
function selectModel(id){$('modelButton').click();const button=w.document.querySelector(`[data-model="${id}"]`);assert.ok(button,id);button.click();}
function send(text){$('messageInput').value=text;$('messageInput').dispatchEvent(new w.Event('input'));$('composerForm').dispatchEvent(new w.Event('submit',{cancelable:true}));}
const assistant=()=>saved[0]?.messages.at(-1);
async function finished(label){await until(()=>assistant()?.role==='assistant'&&!assistant().streaming,label);assert.ok(!assistant().error,assistant().error);}
selectModel('veo-3.1-fast-generate-preview');send('生成一个挖掘机施工视频');await finished('Veo video');
assert.equal(videoCount,1);assert.equal(assistant().media[0].type,'video');assert.ok($('messages').querySelector('video'));
assert.equal(assistant().task.status,'completed');
$('newChatTopButton').click();selectModel('mimo-v2.5-tts');send('你好，测试声音');await finished('MiMo TTS');assert.equal(assistant().media[0].type,'audio');assert.ok($('messages').querySelector('audio'));
$('newChatTopButton').click();selectModel('speech-2.8-hd');send('欢迎使用雷火');await finished('MiniMax TTS');assert.equal(assistant().media[0].type,'audio');
$('newChatTopButton').click();selectModel('gpt-image-2');send('生成一只橘猫');await finished('image');assert.equal(assistant().media[0].type,'image');
$('newChatTopButton').click();selectModel('mimo-v2.5-asr');
const wav=new Uint8Array(48);wav.set(Buffer.from('RIFF'),0);wav.set(Buffer.from('WAVE'),8);
Object.defineProperty($('audioInput'),'files',{configurable:true,value:[new w.File([wav],'record.wav',{type:'audio/wav'})]});$('audioInput').onchange();
await until(()=>$('attachmentPreview').children.length===1,'audio attachment');send('转写音频');await finished('ASR');assert.equal(assistant().content,'这是转写结果。');
$('newChatTopButton').click();selectModel('seed-tts-2.0');const before=calls.length;send('同步后的语音');await pause(20);
assert.equal(calls.length,before,'Seed must not invent an endpoint');assert.equal($('messageInput').value,'同步后的语音');
$('syncInterfacesButton').click();send('同步后的语音');await finished('Seed after interface sync');assert.equal(assistant().media[0].type,'audio');
$('newChatTopButton').click();selectModel('veo-3.1-fast-generate-preview');send('停止后继续查询');
await until(()=>assistant()?.task?.id==='task-2'&&assistant()?.streaming,'submitted task persisted');$('stopButton').click();
await until(()=>assistant()?.task?.status==='paused'&&!assistant().streaming,'paused task');assert.equal($('stopButton').hidden,true);
finishPaused=true;w.document.querySelector('[data-resume-task]').click();await finished('resumed task');assert.equal(videoCount,2,'Resume must not POST again');
$('newChatTopButton').click();selectModel('veo-3.1-fast-generate-preview');send('获取无地址的已完成视频');await finished('video content');assert.equal(assistant().media[0].type,'video');
const postCount=calls.filter(call=>call.endpoint==='/v1/video/generations').length;assert.equal(postCount,3);
console.log('PASS: Veo submit/query/playback, MiMo/MiniMax audio, image, ASR upload, Seed synced route, stop/resume without duplicate POST, completed-video content fallback. No live model requests.');
dom.window.close();
