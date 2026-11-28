// G.711 µ-law, one byte per 16-bit sample: the voice channel of a support session carries 20 ms frames of
// 16 kHz mono speech encoded this way. Same maths as the agent's MuLaw.kt (the two ends must agree bit for bit).

export const SAMPLE_RATE = 16_000;
/** Samples (and bytes on the wire) in one 20 ms frame. */
export const FRAME_SAMPLES = 320;

const BIAS = 0x84;
const CLIP = 32_635;

/** One signed 16-bit sample → one µ-law byte (0..255). */
export function encodeSample(sample: number): number {
  let pcm = sample | 0;
  const sign = pcm < 0 ? 0x80 : 0;
  if (pcm < 0) pcm = -pcm;
  if (pcm > CLIP) pcm = CLIP;
  pcm += BIAS;
  let exponent = 7;
  let mask = 0x4000;
  while (exponent > 0 && (pcm & mask) === 0) {
    exponent--;
    mask >>= 1;
  }
  const mantissa = (pcm >> (exponent + 3)) & 0x0f;
  return ~(sign | (exponent << 4) | mantissa) & 0xff;
}

/** One µ-law byte → one signed 16-bit sample. */
export function decodeSample(byte: number): number {
  const u = ~byte & 0xff;
  const sign = u & 0x80;
  const exponent = (u >> 4) & 0x07;
  const mantissa = u & 0x0f;
  const magnitude = (((mantissa << 3) + BIAS) << exponent) - BIAS;
  return sign !== 0 ? -magnitude : magnitude;
}

const DECODE = new Int16Array(256);
for (let i = 0; i < 256; i++) DECODE[i] = decodeSample(i);

export function encode(pcm: Int16Array, count: number = pcm.length): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(count);
  for (let i = 0; i < count; i++) out[i] = encodeSample(pcm[i]);
  return out;
}

export function decode(data: Uint8Array, offset = 0, count: number = data.length - offset): Int16Array<ArrayBuffer> {
  const n = Math.max(0, count);
  const out = new Int16Array(n);
  for (let i = 0; i < n; i++) out[i] = DECODE[data[offset + i]];
  return out;
}

/** Root mean square of the samples (0..32767): how loud a frame is. */
export function rms(pcm: Int16Array, count: number = pcm.length): number {
  if (count <= 0) return 0;
  let sum = 0;
  for (let i = 0; i < count; i++) sum += pcm[i] * pcm[i];
  return Math.floor(Math.sqrt(sum / count));
}
