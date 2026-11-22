// Renders the app's AGSL holo shader in WebGL (headless Chromium) for visual tuning.
// usage: node render.js <shader.agsl> <out.png> <tileWidth> <spec.json>
// spec: [{"image": "path.jpg", "mode": 4, "label": "Secret", "photo": 1, "art": [x0,y0,x1,y1]}], tilts below
const { chromium } = require('playwright');
const fs = require('fs');
const [,, shaderPath, outPath, tileW, specPath, tiltsArg] = process.argv;
const core = fs.readFileSync(shaderPath, 'utf8');
const spec = JSON.parse(fs.readFileSync(specPath, 'utf8'));
const tilts = tiltsArg ? JSON.parse(tiltsArg) : [[-0.8, -0.5], [0, 0], [0.8, 0.5]];
const W = parseInt(tileW), H = Math.round(W * 86 / 59);
const prefix = `precision highp float;
#define float2 vec2
#define float3 vec3
#define float4 vec4
#define half4 vec4
uniform sampler2D uImage;
float4 sampleCard(float2 uv) { return texture2D(uImage, uv); }
`;
const fragment = prefix + core + `
void main() { vec2 uv = gl_FragCoord.xy / resolution; uv.y = 1.0 - uv.y; gl_FragColor = shade(uv); }`;
const images = spec.map(s => 'data:image/jpeg;base64,' + fs.readFileSync(s.image).toString('base64'));
(async () => {
  const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  page.on('console', m => console.log('page:', m.text()));
  await page.setContent('<html><body style="margin:0;background:#111"></body></html>');
  const png = await page.evaluate(async ({ fragment, images, spec, tilts, W, H }) => {
    const gl = document.createElement('canvas').getContext('webgl', { preserveDrawingBuffer: true });
    const glc = gl.canvas; glc.width = W; glc.height = H; gl.viewport(0, 0, W, H);
    const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s)); return s; };
    const prog = gl.createProgram();
    gl.attachShader(prog, sh(gl.VERTEX_SHADER, 'attribute vec2 p; void main(){ gl_Position = vec4(p,0.,1.); }'));
    gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, fragment));
    gl.linkProgram(prog); gl.useProgram(prog);
    const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
    const loc = gl.getAttribLocation(prog, 'p'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
    const U = n => gl.getUniformLocation(prog, n);
    const pad = 12, label = 22;
    const out = document.createElement('canvas');
    out.width = tilts.length * (W + pad) + pad; out.height = spec.length * (H + pad + label) + pad;
    const ctx = out.getContext('2d'); ctx.fillStyle = '#16161a'; ctx.fillRect(0, 0, out.width, out.height);
    ctx.font = '15px sans-serif';
    for (let r = 0; r < spec.length; r++) {
      const img = new Image(); img.src = images[r]; await img.decode();
      const tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      const s = spec[r];
      gl.uniform2f(U('resolution'), W, H);
      gl.uniform2f(U('texel'), 1 / img.naturalWidth, 1 / img.naturalHeight);
      gl.uniform1f(U('mode'), s.mode); gl.uniform1f(U('photo'), s.photo); gl.uniform1f(U('time'), 0);
      gl.uniform4f(U('artRect'), ...s.art);
      for (let c = 0; c < tilts.length; c++) {
        gl.uniform2f(U('tilt'), tilts[c][0], tilts[c][1]);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const x = pad + c * (W + pad), y = pad + r * (H + pad + label);
        ctx.drawImage(glc, x, y + label);
        ctx.fillStyle = '#ddd'; ctx.fillText(`${s.label}   tilt ${tilts[c][0]}, ${tilts[c][1]}`, x, y + 16);
      }
    }
    return out.toDataURL('image/png');
  }, { fragment, images, spec, tilts, W, H });
  fs.writeFileSync(outPath, Buffer.from(png.split(',')[1], 'base64'));
  await browser.close();
})().catch(e => { console.error(e.message); process.exit(1); });
