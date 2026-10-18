import {defineConfig} from 'vite';
import {copyFileSync} from 'node:fs';
export default defineConfig({base:'./',build:{target:'es2022',rollupOptions:{input:{options:'options.html',popup:'popup.html',background:'src/background/index.ts'},output:{entryFileNames:'[name].js',chunkFileNames:'chunks/[name]-[hash].js'}}},plugins:[{name:'copy-manifest',closeBundle(){copyFileSync('manifest.json','dist/manifest.json');}}]});
