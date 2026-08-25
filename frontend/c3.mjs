import fs from 'node:fs';
import path from 'node:path';
import ts from 'typescript';

const APP='app', MSG='messages';
function files(d){const o=[];for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())o.push(...files(p));else if(/\.tsx?$/.test(e.name))o.push(p);}return o;}
function merged(loc){const out={};for(const f of fs.readdirSync(path.join(MSG,loc))){Object.assign(out,JSON.parse(fs.readFileSync(path.join(MSG,loc,f),'utf8')));}return out;}
const M={en:merged('en'),ja:merged('ja')};
function res(loc,key){let c=M[loc];for(const s of key.split('.')){if(typeof c!=='object'||c===null||Array.isArray(c))return undefined;if(!(s in c))return undefined;c=c[s];}return c;}
function lits(e){if(ts.isStringLiteralLike(e))return[e.text];if(ts.isParenthesizedExpression(e))return lits(e.expression);if(ts.isConditionalExpression(e))return[...lits(e.whenTrue),...lits(e.whenFalse)];return[{NON_LITERAL:e.getText().slice(0,60)}];}
const rows=[];
for(const p of files(APP)){
  const src=fs.readFileSync(p,'utf8');
  if(!src.includes('useApiErrorToast'))continue;
  const sf=ts.createSourceFile(p,src,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);
  const rep=new Map();
  (function walk(n){if(ts.isVariableDeclaration(n)&&ts.isIdentifier(n.name)&&n.initializer&&ts.isCallExpression(n.initializer)&&ts.isIdentifier(n.initializer.expression)&&n.initializer.expression.text==='useApiErrorToast'){const a=n.initializer.arguments[0];rep.set(n.name.text,a&&ts.isStringLiteralLike(a)?a.text:(a?'<NONLIT>':null));}ts.forEachChild(n,walk);})(sf);
  (function walk(n){if(ts.isCallExpression(n)&&ts.isIdentifier(n.expression)&&rep.has(n.expression.text)){const f=n.arguments[1];const ns=rep.get(n.expression.text);const line=sf.getLineAndCharacterOfPosition(n.getStart()).line+1;
    if(!f){rows.push({p,line,ns,key:null});}
    else for(const k of lits(f)){rows.push({p,line,ns,key:k});}}
   ts.forEachChild(n,walk);})(sf);
}
let bad=0;
for(const r of rows){
  if(r.key===null){continue;}
  if(typeof r.key!=='string'){console.log('NONLITERAL',r.p+':'+r.line,r.ns,JSON.stringify(r.key));continue;}
  const q=r.ns===null?r.key:r.ns+'.'+r.key;
  for(const loc of ['en','ja']){
    const v=res(loc,q);
    if(typeof v!=='string'){console.log('MISSING',loc,q,'at',r.p+':'+r.line,'->',JSON.stringify(v));bad++;}
    else if(v.includes('{')){console.log('PLACEHOLDER',loc,q,JSON.stringify(v));bad++;}
  }
}
for(const r of rows){if(typeof r.key!=='string'||r.key===null)continue;const q=r.ns===null?r.key:r.ns+'.'+r.key;const v=res('en',q);if(typeof v==='string'&&(v.length>46||/[.!?]$/.test(v)))console.log('LONG',q,JSON.stringify(v));}
console.log('callsites',rows.length,'bad',bad);
