// Compiles the app's exact OpenGL ES shaders (written by HoloTest.writeGlShaders to app/build/holo)
// in WebGL and renders them through the same pipeline as HoloGLView: shadow, then the tilted card.
// usage: node gl-check.js <dir with card.vert/card.frag/shadow.frag> <out.png> <spec.json> [tilts]
const { chromium } = require('playwright');
const fs = require('fs');
const [,, dir, outPath, specPath, tiltsArg] = process.argv;
const vert = fs.readFileSync(`${dir}/card.vert`, 'utf8');
const frag = fs.readFileSync(`${dir}/card.frag`, 'utf8');
const shadow = fs.readFileSync(`${dir}/shadow.frag`, 'utf8');
const spec = JSON.parse(fs.readFileSync(specPath, 'utf8'));
const tilts = tiltsArg ? JSON.parse(tiltsArg) : [[-0.7, -0.4], [0, 0], [0.7, 0.45]];
const images = spec.map(s => 'data:image/jpeg;base64,' + fs.readFileSync(s.image).toString('base64'));
const VW = parseInt(process.env.VW || '300'), VH = Math.round(VW * 1.4); // a phone-like viewport per tile
(async () => {
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  await page.setContent('<html><body></body></html>');
  const png = await page.evaluate(async ({ vert, frag, shadow, images, spec, tilts, VW, VH }) => {
    const glc = document.createElement('canvas'); glc.width = VW; glc.height = VH;
    const gl = glc.getContext('webgl', { preserveDrawingBuffer: true, premultipliedAlpha: true, alpha: true });
    const compile = (type, src, name) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(name + ': ' + gl.getShaderInfoLog(s)); return s; };
    const link = (v, f, name) => { const p = gl.createProgram(); gl.attachShader(p, compile(gl.VERTEX_SHADER, v, name + '.vert'));
      gl.attachShader(p, compile(gl.FRAGMENT_SHADER, f, name + '.frag')); gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(name + ' link: ' + gl.getProgramInfoLog(p)); return p; };
    const card = link(vert, frag, 'card'), shad = link(vert, shadow, 'shadow');
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-0.5,-0.5, 0.5,-0.5, -0.5,0.5, 0.5,0.5]), gl.STATIC_DRAW);
    const U = (p, n, f) => { const l = gl.getUniformLocation(p, n); if (l) f(l); };
    const aspect = VW / VH;
    let h = 1.84, w = h * 59 / 86; if (w > aspect * 1.84) { w = aspect * 1.84; h = w * 86 / 59; }
    const draw = (p, ax, ay, ox, oy, sc) => { gl.useProgram(p);
      U(p, 'uCardSize', l => gl.uniform2f(l, w, h)); U(p, 'uAngles', l => gl.uniform2f(l, ax, ay));
      U(p, 'uOffset', l => gl.uniform2f(l, ox, oy)); U(p, 'uScale', l => gl.uniform1f(l, sc)); U(p, 'uAspect', l => gl.uniform1f(l, aspect));
      const loc = gl.getAttribLocation(p, 'aCorner'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); };
    const pad = 10, label = 20;
    const out = document.createElement('canvas');
    out.width = tilts.length * (VW + pad) + pad; out.height = spec.length * (VH + pad + label) + pad;
    const ctx = out.getContext('2d'); ctx.font = '14px sans-serif';
    const bg = ctx.createLinearGradient(0, 0, 0, out.height); bg.addColorStop(0, '#22222c'); bg.addColorStop(1, '#050507');
    ctx.fillStyle = bg; ctx.fillRect(0, 0, out.width, out.height);
    for (let r = 0; r < spec.length; r++) {
      const img = new Image(); img.src = images[r]; await img.decode();
      const tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.LINEAR));
      [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T].forEach(k => gl.texParameteri(gl.TEXTURE_2D, k, gl.CLAMP_TO_EDGE));
      // Same centre-crop as HoloGLView.setCard
      const t = 59 / 86, iw = img.naturalWidth, ih = img.naturalHeight;
      const crop = iw / ih > t ? [(iw - ih * t) / 2 / iw, 0, ih * t / iw, 1] : [0, (ih - iw / t) / 2 / ih, 1, iw / t / ih];
      const s = spec[r];
      for (let c = 0; c < tilts.length; c++) {
        const [tx, ty] = tilts[c];
        gl.viewport(0, 0, VW, VH); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
        gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        const ax = -ty * 0.30, ay = tx * 0.30;
        draw(shad, ax, ay, -tx * 0.05, -0.06 - ty * 0.04, 1.04);
        gl.useProgram(card);
        U(card, 'uImage', l => gl.uniform1i(l, 0)); U(card, 'uSrc', l => gl.uniform4f(l, ...crop));
        U(card, 'texel', l => gl.uniform2f(l, 1 / (iw * crop[2]), 1 / (ih * crop[3])));
        U(card, 'tilt', l => gl.uniform2f(l, tx, ty)); U(card, 'time', l => gl.uniform1f(l, 0));
        U(card, 'mode', l => gl.uniform1f(l, s.mode)); U(card, 'photo', l => gl.uniform1f(l, s.photo));
        U(card, 'artRect', l => gl.uniform4f(l, ...s.art)); U(card, 'resolution', l => gl.uniform2f(l, VW, VH));
        draw(card, ax, ay, 0, 0, 1);
        const x = pad + c * (VW + pad), y = pad + r * (VH + pad + label);
        ctx.drawImage(glc, x, y + label);
        ctx.fillStyle = '#ddd'; ctx.fillText(`${s.label}   tilt ${tx}, ${ty}`, x, y + 15);
      }
    }
    return out.toDataURL('image/png');
  }, { vert, frag, shadow, images, spec, tilts, VW, VH });
  fs.writeFileSync(outPath, Buffer.from(png.split(',')[1], 'base64'));
  await browser.close();
  console.log('OK: shaders compiled and rendered');
})().catch(e => { console.error('FAILED:', e.message); process.exit(1); });
