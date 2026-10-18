// These self-contained functions are serialized by chrome.scripting.executeScript.
// They never receive device keys, relay credentials or account data.
export function captureTarget(useContext:boolean):{fieldToken:string;origin:string}|null{
 const w=window as unknown as {__otpbridge?:{last:Element|null;lastAt:number;targets:Map<string,{element:HTMLInputElement;value:string}>;listening:boolean}};
 const state=w.__otpbridge??={last:null,lastAt:0,targets:new Map(),listening:false};
 if(!state.listening){document.addEventListener('contextmenu',e=>{if(e.isTrusted){state.last=e.target instanceof Element?e.target:null;state.lastAt=Date.now();}},true);state.listening=true;}
 // activeTab is granted after the first menu click, too late to observe that
 // right-click. Fail closed: install the listener and require one more right-click.
 // Never guess that the focused field is the field the user actually clicked.
 const candidate=useContext?(Date.now()-state.lastAt<30_000?state.last:null):document.activeElement;
 if(!(candidate instanceof HTMLInputElement)||candidate.disabled||candidate.readOnly||!['text','tel','number','password',''].includes(candidate.type)||!candidate.isConnected)return null;
 if(window.top!==window && location.ancestorOrigins[0]!==location.origin)return null;
 const token=crypto.randomUUID();state.targets.clear();state.targets.set(token,{element:candidate,value:candidate.value});state.last=null;
 return {fieldToken:token,origin:location.origin};
}
export function fillTarget(token:string,origin:string,otp:string,requestExpiresAt:number=0,validUntil:number=0):boolean{
 const fresh=()=>requestExpiresAt>Date.now()&&validUntil-Date.now()>=2000;
 if(!fresh()||location.origin!==origin||!/^(?:\d{6}|\d{8})$/.test(otp))return false;
 const w=window as unknown as {__otpbridge?:{targets:Map<string,{element:HTMLInputElement;value:string}>}};
 const entry=w.__otpbridge?.targets.get(token);w.__otpbridge?.targets.delete(token);
 if(!entry)return false;const el=entry.element;
 if(!el.isConnected||el.disabled||el.readOnly||el.value!==entry.value||document.activeElement!==el)return false;
 const set=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;if(!set)return false;
 // Split widgets are deliberately rejected until the entire original group can
 // be bound at capture time and revalidated across synchronous page callbacks.
 if(el.maxLength>0&&el.maxLength<otp.length)return false;
 if(!fresh())return false;
 set.call(el,otp);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
 return true;
}
