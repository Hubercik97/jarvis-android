import type { LocalAction } from '../shared/types';
export function localCommand(text:string):LocalAction|null {
 const normalized=text.normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/ł/g,'l').toLowerCase().replace(/^\s*jarvis[, :]*\s*/,'').replace(/[.!?]+$/,'').trim();
 if(/^(otworz|uruchom) (google )?chrome$/.test(normalized))return 'open_chrome';
 if(/^otworz (folder )?dokumenty$/.test(normalized))return 'open_documents';
 if(/^otworz (moj )?projekt( na githubie)?$/.test(normalized))return 'open_project';
 return null;
}

export function isProjectStatus(text:string):boolean {
 const normalized=text.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/ł/g,'l').replace(/^\s*jarvis[, :]*\s*/,'').replace(/[.!?]+$/,'').trim();
 return /^(sprawdz|pokaz) (stan|status) (mojego )?projektu$/.test(normalized);
}
