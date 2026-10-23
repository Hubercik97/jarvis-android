// IPC exceptions may be written to Electron stderr. Recreate errors using only
// a bounded message with known credentials removed; never forward provider bodies.
export function publicError(error:unknown,secrets:string[]=[]):string{
 let value=error instanceof Error?error.message:'Operacja nie powiodła się.';
 value=value.replace(/^Error invoking remote method '[^']+':\s*(?:Error:\s*)?/,'');
 for(const secret of secrets)if(secret.length>=8){value=value.split(secret).join('[UKRYTO]');value=value.split(encodeURIComponent(secret)).join('[UKRYTO]');}
 value=value.replace(/\b(sk-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9_]{8,}|AIza[A-Za-z0-9_-]{12,})/g,'[UKRYTO]');
 return value.slice(0,350);
}
