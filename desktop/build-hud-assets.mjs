import {copyFile} from 'node:fs/promises';
await copyFile('src/hologram/hud.css','../hologram/hud.css');
await copyFile('node_modules/three/LICENSE','../hologram/LICENSE-three.txt');
