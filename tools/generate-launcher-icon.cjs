#!/usr/bin/env node
// Single geometry source for Android adaptive layers, legacy bitmaps and previews.
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const root = path.resolve(__dirname, '..');
const res = path.join(root, 'android-app/app/src/main/res');
const out = path.join(root, 'docs/assets/icon');
const arc = 'M69.678,36.322 A25,25 0,1 0,69.678,71.678';
const play = 'M49,42.8 C49,40.9 51.05,39.72 52.7,40.67 L72.1,51.87 C73.73,52.81 73.73,55.19 72.1,56.13 L52.7,67.33 C51.05,68.28 49,67.1 49,65.2 Z';
const xmlStart = '<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">';
function write(file, content) { fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, content); }
const gradient = (attribute, start, end, color1, color2) => `<aapt:attr name="android:${attribute}"><gradient android:type="linear" android:startX="${start[0]}" android:startY="${start[1]}" android:endX="${end[0]}" android:endY="${end[1]}" android:startColor="${color1}" android:endColor="${color2}" /></aapt:attr>`;
const foreground = `${xmlStart}
    <group android:translateX="4">
    <path android:pathData="${arc}" android:fillColor="@android:color/transparent" android:strokeWidth="8" android:strokeLineCap="round">
        ${gradient('strokeColor', [27,29],[71,81], '#F0D6A6','#B78B4F')}
    </path>
    <path android:pathData="${play}">
        ${gradient('fillColor',[49,40],[72,68],'#FFF0D4','#E0BA7C')}
    </path>
    </group>
</vector>\n`;
const monochrome = `${xmlStart}
    <group android:translateX="4">
    <path android:pathData="${arc}" android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFF" android:strokeWidth="8" android:strokeLineCap="round" />
    <path android:pathData="${play}" android:fillColor="#FFFFFF" />
    </group>
</vector>\n`;
const background = `${xmlStart}
    <path android:pathData="M0,0H108V108H0Z">
        ${gradient('fillColor',[12,0],[87,108],'#293239','#10151A')}
    </path>
</vector>\n`;
// AdaptiveIconDrawable exists on API 26+, where vector gradients are supported.
write(path.join(res,'drawable-v26/ic_launcher_foreground.xml'),foreground);
write(path.join(res,'drawable-v26/ic_launcher_background.xml'),background);
write(path.join(res,'drawable-v33/ic_launcher_monochrome.xml'),monochrome);
const adaptive = mono => `<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@drawable/ic_launcher_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />${mono ? '\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' : ''}\n</adaptive-icon>\n`;
write(path.join(res,'mipmap-anydpi-v26/ic_launcher.xml'),adaptive(false));
write(path.join(res,'mipmap-anydpi-v33/ic_launcher.xml'),adaptive(true));

const defs = `<defs>
 <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="12" y1="0" x2="87" y2="108"><stop stop-color="#293239"/><stop offset="1" stop-color="#10151A"/></linearGradient>
 <linearGradient id="ring" gradientUnits="userSpaceOnUse" x1="27" y1="29" x2="71" y2="81"><stop stop-color="#F0D6A6"/><stop offset="1" stop-color="#B78B4F"/></linearGradient>
 <linearGradient id="play" gradientUnits="userSpaceOnUse" x1="49" y1="40" x2="72" y2="68"><stop stop-color="#FFF0D4"/><stop offset="1" stop-color="#E0BA7C"/></linearGradient>
 </defs>`;
// The open C carries more ink on its left; a 4dp shift optically centers the mark.
const mark = mono => `<g transform="translate(4 0)"><path d="${arc}" fill="none" stroke="${mono ? '#424B3E' : 'url(#ring)'}" stroke-width="8" stroke-linecap="round"/><path d="${play}" fill="${mono ? '#424B3E' : 'url(#play)'}"/></g>`;
function svg(mask='rounded', mono=false, size=1024) {
  // 72dp is the adaptive icon's resting viewport within the 108dp layers.
  const shape = mask === 'circle' ? '<circle cx="54" cy="54" r="36"/>' : '<rect x="18" y="18" width="72" height="72" rx="16"/>';
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="18 18 72 72">${defs}<defs><clipPath id="mask">${shape}</clipPath></defs><g clip-path="url(#mask)"><rect width="108" height="108" fill="${mono ? '#DCE5D5' : 'url(#bg)'}"/>${mark(mono)}</g></svg>`;
}
async function main() {
  fs.mkdirSync(out,{recursive:true});
  write(path.join(out,'cruise-tune-icon.svg'),svg());
  write(path.join(out,'adaptive-foreground.svg'),`<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 108 108">${defs}${mark(false)}</svg>`);
  for (const [density, size] of Object.entries({mdpi:48,hdpi:72,xhdpi:96,xxhdpi:144,xxxhdpi:192})) {
    const target=path.join(res,`mipmap-${density}/ic_launcher.png`); fs.mkdirSync(path.dirname(target),{recursive:true});
    await sharp(Buffer.from(svg('rounded',false,size))).png().toFile(target);
  }
  for (const [name, mask, mono] of [['cruise-tune-icon','rounded',false],['circle','circle',false],['monochrome','rounded',true]]) {
    await sharp(Buffer.from(svg(mask,mono))).png().toFile(path.join(out,`${name}.png`));
  }
  // Contact sheet is a presentation artifact, never included in the application.
  const tile = async (mask, mono, size) => sharp(Buffer.from(svg(mask,mono,size))).png().toBuffer();
  const label=(text,x,y,size=18,color='#737A81')=>`<text x="${x}" y="${y}" font-family="Arial, sans-serif" font-size="${size}" fill="${color}">${text}</text>`;
  const board=`<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="780"><rect width="1280" height="780" fill="#F5F3EF"/>${label('CRUISE TUNE',68,69,18,'#8B744C')}${label('Launcher icon',68,114,32,'#1B2228')}<line x1="68" y1="150" x2="1212" y2="150" stroke="#DDDED9"/>${label('PRIMARY',68,686,15)}${label('CIRCULAR MASK',665,425,14)}${label('THEMED',940,425,14)}${label('48 PX',665,647,14)}${label('72 PX',800,647,14)}${label('96 PX',957,647,14)}${label('0.5.2  /  Launcher icon',965,735,15)}</svg>`;
  await sharp(Buffer.from(board)).composite([
    {input:await tile('rounded',false,440),left:68,top:202},
    {input:await tile('circle',false,176),left:665,top:208},
    {input:await tile('rounded',true,176),left:940,top:208},
    {input:await tile('rounded',false,48),left:665,top:537},
    {input:await tile('rounded',false,72),left:800,top:525},
    {input:await tile('rounded',false,96),left:957,top:513}
  ]).png().toFile(path.join(out,'icon-presentation.png'));
  console.log('Generated adaptive layers, API 23–25 density bitmaps, monochrome layer and PNG/SVG previews.');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
