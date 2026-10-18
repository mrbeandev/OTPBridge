export const MAX_BODY=20_000;
export const MAX_PAYLOAD=16_384;
export const MAX_MESSAGES=64;
export const PAIR_TTL=300_000;
export const DEVICE_TTL=90*24*60*60*1000;
export const ID=/^[A-Za-z0-9_-]{43}$/;
export const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer'}});
export async function boundedJson(request:Request):Promise<any>{
  if(!request.headers.get('Content-Type')?.startsWith('application/json')) throw new Error('content-type');
  const reader=request.body?.getReader(); if(!reader) throw new Error('body');
  let total=0;const chunks:Uint8Array[]=[];
  let timer:ReturnType<typeof setTimeout>|undefined;
  const deadline=new Promise<never>((_,reject)=>{timer=setTimeout(()=>{void reader.cancel();reject(new Error('body timeout'));},5000);});
  try{while(true){const {value,done}=await Promise.race([reader.read(),deadline]);if(done)break;total+=value.length;if(total>MAX_BODY){await reader.cancel();throw new Error('size');}chunks.push(value);}}finally{if(timer!==undefined)clearTimeout(timer);reader.releaseLock();}
  const all=new Uint8Array(total);let offset=0;for(const c of chunks){all.set(c,offset);offset+=c.length;}return JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(all));
}
export function randomId(){const bytes=crypto.getRandomValues(new Uint8Array(32));return btoa(String.fromCharCode(...bytes)).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');}
export async function hash(value:string){const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value));return Array.from(new Uint8Array(bytes),b=>b.toString(16).padStart(2,'0')).join('');}
