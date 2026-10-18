import {boundedJson,hash,ID,json,randomId} from './limits';
export {Mailbox} from './mailbox';
export interface Env {MAILBOX:DurableObjectNamespace}
export default {
  async fetch(request:Request,env:Env):Promise<Response>{
    const u=new URL(request.url);
    if(request.method==='GET'&&u.pathname==='/health')return json({ok:true,protocolVersions:[1]});
    try{
      if(u.pathname==='/v1/channels'&&request.method==='POST'){
        const body=await boundedJson(request);if(!body||Object.keys(body).length)return json({error:'Invalid body'},400);
        // Rate-limit enrollment with a separate bounded DO bucket, not in isolate memory.
        const ip=request.headers.get('CF-Connecting-IP')??'local';
        const bucket=await hash(`enroll:${ip}`);
        const gate=env.MAILBOX.get(env.MAILBOX.idFromName(`rate:${bucket}`));
        const allowed=await gate.fetch(new Request('https://internal/rate',{method:'POST'}));
        if(!allowed.ok)return json({error:'Enrollment rate limited'},429);
        const channelId=randomId(),browserToken=randomId(),phoneToken=randomId();
        const expiresAt=Date.now()+300_000;
        const stub=env.MAILBOX.get(env.MAILBOX.idFromName(channelId));
        const initialized=await stub.fetch(new Request('https://internal/init',{method:'POST',body:JSON.stringify({browserHash:await hash(browserToken),phoneHash:await hash(phoneToken),expiresAt})}));
        if(!initialized.ok)return json({error:'Unavailable'},503);
        return json({channelId,browserToken,phoneToken,expiresAt},201);
      }
      const match=/^\/v1\/channels\/([^/]+)(\/messages|\/activate)?$/.exec(u.pathname);
      if(!match||!ID.test(match[1]!))return json({error:'Not found'},404);
      const suffix=match[2]??'';
      if(!((suffix==='/messages'&&['GET','POST'].includes(request.method))||(suffix==='/activate'&&request.method==='POST')||(!suffix&&request.method==='DELETE')))return json({error:'Method not allowed'},405);
      if(!/^Bearer [A-Za-z0-9_-]{43}$/.test(request.headers.get('Authorization')??''))return json({error:'Unauthorized'},401);
      const stub=env.MAILBOX.get(env.MAILBOX.idFromName(match[1]!));
      const url=new URL(`https://internal${suffix||'/channel'}`);url.search=u.search;
      return await stub.fetch(new Request(url,request));
    }catch{return json({error:'Invalid request'},400);}
  }
} satisfies ExportedHandler<Env>;
