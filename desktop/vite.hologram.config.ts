import {defineConfig} from 'vite';
export default defineConfig({build:{outDir:'../hologram',emptyOutDir:true,copyPublicDir:false,lib:{entry:'src/hologram/web.ts',formats:['es'],fileName:()=> 'viewer.js'},target:'es2022',minify:true}});
