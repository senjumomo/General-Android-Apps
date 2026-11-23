// Renders the generated pack (app/build/holo/pack.png, written by HoloTest.packArt) through the app's
// exact OpenGL shaders, mirroring PackGLView: sealed, tearing, and the rip animation.
// usage: node gl-pack.js <app/build/holo> <out.png> [frames.json: [[label, tiltX, tiltY, tearProgress, ripT], ...]]
const { chromium } = require('playwright');
const fs = require('fs');
const [,, dir, outPath, framesArg] = process.argv;
const vert = fs.readFileSync(`${dir}/card.vert`, 'utf8');
const frag = fs.readFileSync(`${dir}/card.frag`, 'utf8');
const shadow = fs.readFileSync(`${dir}/shadow.frag`, 'utf8');
const img = 'data:image/png;base64,' + fs.readFileSync(`${dir}/pack.png`).toString('base64');
const VW = 300, VH = 480, PW = 640, PH = 1110, TEAR_Y = 0.115;
// [label, tiltX, tiltY, tearProgress, ripT]
const frames = framesArg ? JSON.parse(require('fs').readFileSync(framesArg, 'utf8')) : [['Sealed', -0.5, -0.2, 0, 0], ['Sealed (tilted)', 0.6, 0.35, 0, 0], ['Tearing 60%', 0.1, 0, 0.6, 0],
                ['Rip 20%', 0.1, 0, 1, 0.2], ['Rip 45%', 0.1, 0, 1, 0.45], ['Rip 70%', 0.1, 0, 1, 0.7]];
(async () => {
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  await page.setContent('<html><body></body></html>');
  const png = await page.evaluate(async ({ vert, frag, shadow, img, frames, VW, VH, PW, PH, TEAR_Y }) => {
    const glc = document.createElement('canvas'); glc.width = VW; glc.height = VH;
    const gl = glc.getContext('webgl', { preserveDrawingBuffer: true, premultipliedAlpha: true });
    const sh = (t, s, n) => { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x);
      if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw new Error(n + ': ' + gl.getShaderInfoLog(x)); return x; };
    const link = (v, f, n) => { const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, v, n)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, f, n));
      gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(n + ' link ' + gl.getProgramInfoLog(p)); return p; };
    const card = link(vert, frag, 'card'), shad = link(vert, shadow, 'shadow');
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-0.5,-0.5, 0.5,-0.5, -0.5,0.5, 0.5,0.5]), gl.STATIC_DRAW);
    const im = new Image(); im.src = img; await im.decode();
    const tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, im);
    [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.LINEAR));
    [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.CLAMP_TO_EDGE));
    const aspect = VW / VH;
    let h = 1.7, w = h * PW / PH; if (w > aspect * 1.6) { w = aspect * 1.6; h = w * PH / PW; }
    const U = (p, n, f) => { const l = gl.getUniformLocation(p, n); if (l) f(l); };
    const draw = (p, ax, ay, ox, oy, sc, rz, mode, prog, fade, tx, ty) => { gl.useProgram(p);
      U(p,'uCardSize',l=>gl.uniform2f(l,w,h)); U(p,'uAngles',l=>gl.uniform2f(l,ax,ay)); U(p,'uOffset',l=>gl.uniform2f(l,ox,oy));
      U(p,'uScale',l=>gl.uniform1f(l,sc)); U(p,'uAspect',l=>gl.uniform1f(l,aspect)); U(p,'uRotZ',l=>gl.uniform1f(l,rz));
      U(p,'uShapeAspect',l=>gl.uniform1f(l,PH/PW)); U(p,'uCornerRadius',l=>gl.uniform1f(l,0.012)); U(p,'uFade',l=>gl.uniform1f(l,fade));
      if (p === card) { U(p,'uImage',l=>gl.uniform1i(l,0)); U(p,'uSrc',l=>gl.uniform4f(l,0,0,1,1)); U(p,'texel',l=>gl.uniform2f(l,1/PW,1/PH));
        U(p,'tilt',l=>gl.uniform2f(l,tx,ty)); U(p,'time',l=>gl.uniform1f(l,0)); U(p,'mode',l=>gl.uniform1f(l,13)); U(p,'photo',l=>gl.uniform1f(l,0));
        U(p,'artRect',l=>gl.uniform4f(l,0,0,1,1)); U(p,'uTearY',l=>gl.uniform1f(l,TEAR_Y)); U(p,'uTearMode',l=>gl.uniform1f(l,mode)); U(p,'uTearProgress',l=>gl.uniform1f(l,prog)); U(p,'uPillow',l=>gl.uniform1f(l,1)); }
      const loc = gl.getAttribLocation(p,'aCorner'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc,2,gl.FLOAT,false,0,0);
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4); };
    const pad = 10, label = 20;
    const out = document.createElement('canvas'); out.width = frames.length * (VW + pad) + pad; out.height = VH + label + 2 * pad;
    const ctx = out.getContext('2d'); const g = ctx.createLinearGradient(0,0,0,out.height); g.addColorStop(0,'#22222c'); g.addColorStop(1,'#050507');
    ctx.fillStyle = g; ctx.fillRect(0,0,out.width,out.height); ctx.font = '14px sans-serif';
    frames.forEach(([name, tx, ty, prog, t], i) => {
      gl.viewport(0,0,VW,VH); gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT);
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      const ax = -ty * 0.28, ay = tx * 0.28;
      if (t === 0) {
        draw(shad, ax, ay, -tx*0.05, -0.06-ty*0.04, 1.03, 0, 0, 0, 0, tx, ty);
        draw(card, ax, ay, 0, 0, 1, 0, 0, prog, 0, tx, ty);
      } else {
        const drop = t*t, e = 1-(1-t)*(1-t);
        draw(card, ax*(1-t), ay*(1-t), 0, -drop*2.6, 1, 0, 2, 0, Math.min(Math.max((t-0.35)/0.65,0),1), tx, ty);
        draw(card, ax, ay, e*0.75, -t*0.25, 1, -e*0.9, 1, 0, Math.min(Math.max((t-0.65)/0.35,0),1), tx, ty);
      }
      const x = pad + i * (VW + pad);
      ctx.drawImage(glc, x, pad + label); ctx.fillStyle = '#ddd'; ctx.fillText(name, x, pad + 14);
    });
    return out.toDataURL('image/png');
  }, { vert, frag, shadow, img, frames, VW, VH, PW, PH, TEAR_Y });
  fs.writeFileSync(outPath, Buffer.from(png.split(',')[1], 'base64'));
  await browser.close(); console.log('OK');
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
